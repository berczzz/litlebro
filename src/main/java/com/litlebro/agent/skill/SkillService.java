package com.litlebro.agent.skill;

import com.litlebro.agent.context.SessionContextHolder;
import com.litlebro.agent.skill.model.SkillDefinition;
import com.litlebro.agent.skill.model.SkillExecRequest;
import com.litlebro.agent.skill.model.SkillExecResult;
import com.litlebro.agent.skill.store.SkillStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 技能核心业务服务：注册、记录、鉴权、目录解析与脚本执行编排。
 *
 * <p>技能包为本地目录模型（预置于 {@code app.skill.dir}，运行时零下载）：
 * <pre>
 * {dir}/{skillId}/SKILL.md（必填） scripts/ references/ assets/
 * </pre>
 *
 * <p>安全边界：
 * <ul>
 *   <li>目录根约束：技能目录归一化后必须落在 {@code app.skill.dir} 内，拒绝路径逃逸</li>
 *   <li>脚本约束：exec_skill 的 scriptName 只允许单文件名，且必须存在于技能 scripts/ 目录</li>
*   <li>读取约束：read_skill_file 相对路径归一化后必须落在技能目录内</li>
     *   <li>记录鉴权：load_skill / exec_skill / read_skill_file 每次调用都校验"在本请求可用名单内（global 或已记录）"</li>
 *   <li>执行开关：{@code app.skill.exec.enabled} 默认关闭，开启后才允许 exec_skill</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.skill.enabled", havingValue = "true")
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    /** 技能说明文件名（必填） */
    private static final String SKILL_MD = "SKILL.md";
    /** 单次读取技能说明/文件的最大字符数，防止大文件拉爆上下文 */
    private static final int MAX_CONTENT_CHARS = 200_000;
    /** 扩展名 → 解释器默认映射 */
    private static final Map<String, String> EXT_TO_INTERPRETER = Map.of(
            "py", "python3", "js", "node", "mjs", "node", "ts", "node",
            "sh", "bash", "ps1", "powershell");
    /** 直接执行的扩展名（不经解释器） */
    private static final Set<String> DIRECT_EXEC_EXT = Set.of("exe", "cmd", "bat", "jar");

    private final SkillStore store;
    private final SkillProperties props;
    private final SkillExecutor executor;

    public SkillService(SkillStore store, SkillProperties props, SkillExecutor executor) {
        this.store = store;
        this.props = props;
        this.executor = executor;
        try {
            Files.createDirectories(rootDir());
        } catch (IOException e) {
            throw new IllegalStateException("技能根目录创建失败: " + rootDir(), e);
        }
    }

    // ==================== 注册 ====================

    /**
     * 注册技能：校验定义与技能包目录（目录存在且含 SKILL.md），已存在则拒绝。
     *
     * @param skill 技能定义
     * @return 注册后的技能定义（含 origin/createdAt）
     */
    public SkillDefinition register(SkillDefinition skill) {
        validateDefinition(skill);
        if (store.findById(skill.getSkillId()).isPresent()) {
            throw new IllegalArgumentException("技能已存在: " + skill.getSkillId());
        }
        Path dir = resolveSkillDir(skill);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("技能目录不存在: " + dir);
        }
        if (!Files.isRegularFile(dir.resolve(SKILL_MD))) {
            throw new IllegalArgumentException("技能目录缺少 SKILL.md: " + dir);
        }
        skill.setOrigin(skill.getOrigin() == null ? "DYNAMIC" : skill.getOrigin());
        skill.setCreatedAt(System.currentTimeMillis());
        store.save(skill);
        log.info("技能注册成功 skillId={} dir={} global={}", skill.getSkillId(), dir, skill.isGlobal());
        return skill;
    }

    /**
     * 静态预注册：把 {@code app.skill.skills} 配置中的技能入库（同名跳过，不覆盖动态改动）。
     * 由 Spring 在启动时调用。
     */
    @PostConstruct
    public void registerStaticSkills() {
        for (SkillDefinition skill : props.getSkills()) {
            if (skill == null || skill.getSkillId() == null || skill.getSkillId().isBlank()) {
                continue;
            }
            try {
                if (store.findById(skill.getSkillId()).isPresent()) {
                    log.debug("静态技能已存在，跳过 skillId={}（同名不覆盖动态改动）", skill.getSkillId());
                    continue;
                }
                validateDefinition(skill);
                Path dir = resolveSkillDir(skill);
                if (!Files.isRegularFile(dir.resolve(SKILL_MD))) {
                    log.warn("静态技能 SKILL.md 缺失，跳过 skillId={} dir={}", skill.getSkillId(), dir);
                    continue;
                }
                skill.setOrigin("STATIC");
                skill.setCreatedAt(System.currentTimeMillis());
                store.save(skill);
                log.info("静态技能预注册成功 skillId={} dir={}", skill.getSkillId(), dir);
            } catch (Exception e) {
                log.warn("静态技能预注册失败 skillId={} 原因: {}", skill.getSkillId(), e.getMessage());
            }
        }
    }

    /**
     * 删除技能（含其全部会话技能记录）。
     *
     * @param skillId 技能 ID
     */
    public void delete(String skillId) {
        requireSkill(skillId);
        store.delete(skillId);
        log.info("技能已删除 skillId={}", skillId);
    }

    /**
     * 枚举全部技能。
     *
     * @return 技能定义列表
     */
    public List<SkillDefinition> listSkills() {
        return store.findAll();
    }

    private void validateDefinition(SkillDefinition skill) {
        if (skill == null) {
            throw new IllegalArgumentException("技能定义不能为空");
        }
        String skillId = skill.getSkillId();
        if (skillId == null || skillId.isBlank()) {
            throw new IllegalArgumentException("skillId 不能为空");
        }
        if (skillId.contains("/") || skillId.contains("\\") || skillId.contains("..")) {
            throw new IllegalArgumentException("skillId 不能包含路径分隔符或 ..");
        }
    }

    // ==================== 会话技能记录 ====================

    /**
     * 解析本次请求的可用技能：global 技能 ∪ 会话已记录技能 ∪ 请求 skillIds（累加记录，无过期）。
     * 请求名单中未注册或未启用的 skillId 抛 {@link IllegalArgumentException}（应用层转 400）。
     *
     * <p>副作用：请求携带非空 skillIds 时，会将其累加记录到该会话（后续请求无需再携带）。
     *
     * @param sessionId       会话 ID（可为 null）
     * @param requestedSkillIds 请求声明要用的技能 ID 列表（可为空）
     * @return 可用技能列表（已过滤未启用项）
     */
    public List<SkillDefinition> resolveUsable(String sessionId, List<String> requestedSkillIds) {
        List<String> requested = requestedSkillIds == null ? List.of() : requestedSkillIds;
        List<String> invalid = new ArrayList<>();
        for (String id : requested) {
            SkillDefinition def = store.findById(id).orElse(null);
            if (def == null || !def.isEnabled()) {
                invalid.add(id);
            }
        }
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException("技能不存在或未启用: " + String.join(", ", invalid));
        }
        // 请求携带技能时累加记录到会话（幂等：同一技能多次请求只记一次）
        if (sessionId != null && !sessionId.isBlank() && !requested.isEmpty()) {
            for (String id : requested) {
                store.record(sessionId, id);
            }
        }
        Set<String> recorded = new HashSet<>(sessionId == null ? List.of() : store.getRecordedSkillIds(sessionId));
        List<SkillDefinition> result = new ArrayList<>();
        for (SkillDefinition skill : store.findAll()) {
            if (!skill.isEnabled()) {
                continue;
            }
            if (skill.isGlobal() || recorded.contains(skill.getSkillId())) {
                result.add(skill);
            }
        }
        return result;
    }

    /**
     * 本次请求是否存在可用技能（用于应用层工具过滤：无可用技能时技能工具不进 LLM 工具列表）。
     *
     * @param sessionId       会话 ID（可为 null）
     * @param requestedSkillIds 请求声明的技能 ID 列表（可为空）
     * @return true 表示有可用技能
     */
    public boolean hasUsableSkills(String sessionId, List<String> requestedSkillIds) {
        return !resolveUsable(sessionId, requestedSkillIds).isEmpty();
    }

    /**
     * 清空会话的全部技能记录（后续请求将只使用 global 技能，除非重新携带 skillIds）。
     *
     * @param sessionId 会话 ID
     */
    public void clearSessionSkills(String sessionId) {
        store.clearSessionSkills(sessionId);
    }

    /**
     * 生成系统提示技能片段：可用技能的 name + description。空则返回空串，供 AgentService 按需注入。
     *
     * @param usable 已解析的可用技能列表（来自 {@link #resolveUsable}）
     * @return 技能提示文本（可能为空串）
     */
    public String getSystemPromptFragment(List<SkillDefinition> usable) {
        if (usable == null || usable.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (SkillDefinition skill : usable) {
            String desc = skill.getDescription() == null ? "" : skill.getDescription();
            lines.add("- " + skill.nameOrId() + (desc.isBlank() ? "" : ": " + desc));
        }
        return "可用技能:\n" + String.join("\n", lines)
                + "\n技能包说明通过 load_skill(skillId) 加载，脚本通过 exec_skill 执行，"
                + "包内参考文件通过 read_skill_file 读取；未在本请求启用的技能会被这些工具拒绝。";
    }

    // ==================== 鉴权 ====================

    /**
     * 校验当前请求是否有权使用指定技能（技能存在 + 启用 + 在本次可用名单内）。
     * 可用名单由 {@link SessionContextHolder} 携带（对话入口已写入解析后的可用列表，含 global）。
     *
     * @param skillId 技能 ID
     * @return 是否可用
     */
    public boolean checkAccess(String skillId) {
        return SessionContextHolder.getSkillIds().contains(skillId);
    }

    // ==================== load_skill ====================

    /**
     * 加载技能说明（SKILL.md）供 LLM 使用。
     *
     * @param skillId 技能 ID
     * @return SKILL.md 文本，失败/无权限返回说明文本
     */
    public String loadContent(String skillId) {
        if (store.findById(skillId).isEmpty()) {
            return "技能不存在: " + skillId;
        }
        if (!checkAccess(skillId)) {
            return "技能 " + skillId + " 未在本请求启用，无法加载。请将 skillId 加入请求的 skillIds 后再试。";
        }
        Path md = resolveSkillDir(requireSkill(skillId)).resolve(SKILL_MD);
        if (!Files.isRegularFile(md)) {
            return "技能 " + skillId + " 缺少 SKILL.md。";
        }
        try {
            String content = Files.readString(md, StandardCharsets.UTF_8);
            if (content.length() > MAX_CONTENT_CHARS) {
                content = content.substring(0, MAX_CONTENT_CHARS) + "\n...[SKILL.md 过长已截断]";
            }
            return content;
        } catch (IOException e) {
            log.warn("读取技能说明失败 skillId={} 原因: {}", skillId, e.getMessage());
            return "读取技能说明失败: " + e.getMessage();
        }
    }

    // ==================== read_skill_file ====================

    /**
     * 读取技能包内相对路径文件（references/ 或 assets/），做路径包含校验。
     *
     * @param skillId      技能 ID
     * @param relativePath 技能包内相对路径
     * @return 文件文本，无权限/非法路径/失败返回说明文本
     */
    public String readFile(String skillId, String relativePath) {
        if (store.findById(skillId).isEmpty()) {
            return "技能不存在: " + skillId;
        }
        if (!checkAccess(skillId)) {
            return "技能 " + skillId + " 未在本请求启用，无法读取。";
        }
        Path dir = resolveSkillDir(requireSkill(skillId));
        Path file = resolveWithin(dir, relativePath);
        if (file == null) {
            return "非法路径: " + relativePath + "（仅允许技能包内相对路径，禁止越出技能目录）";
        }
        if (!Files.isRegularFile(file)) {
            return "文件不存在: " + relativePath;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.length() > MAX_CONTENT_CHARS) {
                content = content.substring(0, MAX_CONTENT_CHARS) + "\n...[文件过长已截断]";
            }
            return content;
        } catch (IOException e) {
            log.warn("读取技能文件失败 skillId={} path={} 原因: {}", skillId, relativePath, e.getMessage());
            return "读取文件失败: " + e.getMessage();
        }
    }

    // ==================== exec_skill ====================

    /**
     * 执行技能脚本：鉴权 → 脚本名安全校验 → 解释器判定（覆盖/扩展名/shebang）→ 白名单 → 执行。
     *
     * @param skillId    技能 ID
     * @param scriptName 脚本文件名（技能 scripts/ 目录下）
     * @param args       位置参数
     * @return 执行结果文本（含退出码与输出）
     */
    public String exec(String skillId, String scriptName, List<String> args) {
        if (store.findById(skillId).isEmpty()) {
            return "技能不存在: " + skillId;
        }
        if (!checkAccess(skillId)) {
            return "技能 " + skillId + " 未在本请求启用，无法执行。";
        }
        if (!props.getExec().isEnabled()) {
            return "脚本执行已禁用（app.skill.exec.enabled=false）。";
        }
        if (scriptName == null || scriptName.isBlank()) {
            return "scriptName 不能为空。";
        }
        if (scriptName.contains("/") || scriptName.contains("\\") || scriptName.contains("..")) {
            return "非法脚本名: " + scriptName + "（仅允许技能 scripts/ 目录下的单个脚本文件名）";
        }

        SkillDefinition skill = requireSkill(skillId);
        Path dir = resolveSkillDir(skill);
        Path scriptFile = dir.resolve("scripts").resolve(scriptName).normalize();
        if (!scriptFile.startsWith(dir) || !Files.isRegularFile(scriptFile)) {
            return "脚本不存在: " + scriptName;
        }

        String interpreter = resolveInterpreter(skill, scriptName, scriptFile);
        if (interpreter == null) {
            return "无法确定脚本解释器（扩展名未映射且无 shebang）: " + scriptName;
        }
        if (!SkillExecutor.DIRECT_EXEC.equals(interpreter)
                && !props.getExec().getInterpreterAllowList().contains(interpreter)) {
            return "解释器不在白名单: " + interpreter;
        }
        String actualInterpreter = platformMap(interpreter);

        try {
            SkillExecRequest request = new SkillExecRequest(
                    scriptName,
                    scriptFile,
                    SkillExecutor.DIRECT_EXEC.equals(actualInterpreter) ? null : actualInterpreter,
                    dir,
                    args,
                    skill.getEnv(),
                    props.getExec().getTimeoutMs(),
                    props.getExec().getOutputMaxChars());
            SkillExecResult result = executor.exec(request);

            StringBuilder sb = new StringBuilder("执行结果: exitCode=").append(result.exitCode());
            if (result.timedOut()) {
                sb.append("（已超时）");
            }
            sb.append("\n").append(result.output());
            if (result.truncated()) {
                sb.append("\n[输出已截断，超出单次返回上限]");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("技能脚本执行失败 skillId={} script={} 原因: {}", skillId, scriptName, e.getMessage());
            return "脚本执行失败: " + e.getMessage();
        }
    }

    // ==================== 解释器判定 ====================

    /**
     * 三层解释器判定：显式覆盖 → 扩展名映射 → shebang；全部未命中返回 null。
     *
     * @param skill      技能定义（含可选的 interpreterMap）
     * @param scriptName 脚本文件名
     * @param scriptFile 脚本文件（用于读 shebang）
     * @return 解释器名或 {@link SkillExecutor#DIRECT_EXEC}；无法判定返回 null
     */
    private String resolveInterpreter(SkillDefinition skill, String scriptName, Path scriptFile) {
        if (skill.getInterpreterMap() != null) {
            String mapped = skill.getInterpreterMap().get(scriptName);
            if (mapped != null && !mapped.isBlank()) {
                return mapped;
            }
        }
        String ext = extensionOf(scriptName);
        String byExt = EXT_TO_INTERPRETER.get(ext);
        if (byExt != null) {
            return byExt;
        }
        if (DIRECT_EXEC_EXT.contains(ext)) {
            return SkillExecutor.DIRECT_EXEC;
        }
        return readShebang(scriptFile);
    }

    /**
     * 读取脚本首行 shebang 提取解释器名（如 #!/usr/bin/env python3 → python3）。
     *
     * @param file 脚本文件
     * @return 解释器名；无 shebang 或读取失败返回 null
     */
    private String readShebang(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String first = reader.readLine();
            if (first == null || !first.startsWith("#!")) {
                return null;
            }
            String body = first.substring(2).trim();
            String[] parts = body.split("\\s+");
            if (parts.length == 0) {
                return null;
            }
            String last = parts[parts.length - 1];
            int slash = Math.max(last.lastIndexOf('/'), last.lastIndexOf('\\'));
            return slash >= 0 ? last.substring(slash + 1) : last;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 平台解释器名映射：Windows 下 python3 → python（常见安装为 python）。
     */
    private String platformMap(String interpreter) {
        if (SkillExecutor.DIRECT_EXEC.equals(interpreter)) {
            return interpreter;
        }
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (win && "python3".equals(interpreter)) {
            return "python";
        }
        return interpreter;
    }

    // ==================== 路径工具 ====================

    /**
     * 技能包根目录（归一化绝对路径）。
     *
     * @return 技能根目录
     */
    public Path rootDir() {
        return Path.of(props.getDir()).toAbsolutePath().normalize();
    }

    /**
     * 解析技能目录：显式 path 或默认 {root}/{skillId}，并强制校验落在根目录内。
     *
     * @param skill 技能定义
     * @return 技能目录绝对路径
     * @throws IllegalArgumentException 目录越出技能根目录时抛出
     */
    public Path resolveSkillDir(SkillDefinition skill) {
        Path root = rootDir();
        String declared = skill.getPath() != null && !skill.getPath().isBlank()
                ? skill.getPath()
                : root.resolve(skill.getSkillId()).toString();
        Path dir = Path.of(declared).toAbsolutePath().normalize();
        if (!dir.startsWith(root)) {
            throw new IllegalArgumentException("技能目录必须在技能根目录内: " + root);
        }
        return dir;
    }

    /**
     * 在基准目录内安全解析相对路径，越出基准目录返回 null。
     *
     * @param base     基准目录（技能目录）
     * @param relative 相对路径
     * @return 归一化后的文件路径；非法（越界/为空）返回 null
     */
    private Path resolveWithin(Path base, String relative) {
        if (relative == null || relative.isBlank()) {
            return null;
        }
        Path candidate = base.resolve(relative).normalize();
        return candidate.startsWith(base) ? candidate : null;
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private SkillDefinition requireSkill(String skillId) {
        return store.findById(skillId)
                .orElseThrow(() -> new IllegalArgumentException("技能不存在: " + skillId));
    }
}