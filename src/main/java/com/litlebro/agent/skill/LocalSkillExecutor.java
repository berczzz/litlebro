package com.litlebro.agent.skill;

import com.litlebro.agent.skill.model.SkillExecRequest;
import com.litlebro.agent.skill.model.SkillExecResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 技能脚本本地执行器：基于 {@link ProcessBuilder} 同步执行，等待结果。
 *
 * <p>安全与资源约束：
 * <ul>
 *   <li>脚本路径由调用方（SkillService）校验并锁定在技能目录内，本执行器不解析任何用户路径</li>
 *   <li>stdin 重定向到空设备，禁止交互式输入</li>
 *   <li>并发读取 stdout/stderr 两个管道，防止输出量大时管道缓冲区写满导致死锁</li>
 *   <li>超时强杀（{@code destroyForcibly}），输出按 {@code maxOutputChars} 截断</li>
 *   <li>v1 无沙箱：脚本以应用进程同权限运行（文档化限制）</li>
 * </ul>
 */
public class LocalSkillExecutor implements SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(LocalSkillExecutor.class);

    @Override
    public SkillExecResult exec(SkillExecRequest request) {
        List<String> command = new ArrayList<>();
        if (request.interpreter() != null && !request.interpreter().isBlank()) {
            command.add(request.interpreter());
        }
        command.add(request.scriptPath().toString());
        if (request.args() != null) {
            command.addAll(request.args());
        }
        // .jar 不能直接执行，需经 java -jar 启动；此处只处理"无解释器直跑 jar"的场景
        if (request.interpreter() == null && command.size() > 0
                && command.get(0).toLowerCase(Locale.ROOT).endsWith(".jar")) {
            command.add(0, "-jar");
            command.add(0, "java");
        }

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (request.workdir() != null) {
                pb.directory(request.workdir().toFile());
            }
            if (request.env() != null && !request.env().isEmpty()) {
                pb.environment().putAll(request.env());
            }
            pb.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));

            log.info("技能脚本执行开始 script={} command={}", request.scriptName(), command);
            long start = System.currentTimeMillis();
            process = pb.start();

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread outThread = drainAsync(process.getInputStream(), stdout, request.maxOutputChars());
            Thread errThread = drainAsync(process.getErrorStream(), stderr, request.maxOutputChars());

            boolean finished = process.waitFor(request.timeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                joinQuietly(outThread);
                joinQuietly(errThread);
                long cost = System.currentTimeMillis() - start;
                log.warn("技能脚本执行超时 script={} timeoutMs={}", request.scriptName(), request.timeoutMs());
                String out = merge(stdout, stderr, request.maxOutputChars());
                return new SkillExecResult(-1, out, true, true, "脚本执行超时（" + request.timeoutMs() + "ms），进程已被强制终止");
            }

            joinQuietly(outThread);
            joinQuietly(errThread);
            int exitCode = process.exitValue();
            String out = merge(stdout, stderr, request.maxOutputChars());
            log.info("技能脚本执行完成 script={} exit={} cost={}ms 输出长度={}", request.scriptName(), exitCode,
                    System.currentTimeMillis() - start, out.length());
            return new SkillExecResult(exitCode, out, out.length() >= request.maxOutputChars(), false, null);
        } catch (Exception e) {
            log.warn("技能脚本执行失败 script={} 原因: {}", request.scriptName(), e.getMessage());
            return new SkillExecResult(-1, "", false, false, "脚本启动失败: " + e.getMessage());
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 合并 stdout 与 stderr，超长按 maxChars 截断并追加提示。
     */
    private String merge(StringBuilder stdout, StringBuilder stderr, int maxChars) {
        StringBuilder sb = new StringBuilder();
        if (stdout.length() > 0) {
            sb.append(stdout);
        }
        if (stderr.length() > 0) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("[stderr]\n").append(stderr);
        }
        if (sb.length() > maxChars) {
            sb.setLength(maxChars);
            sb.append("\n...[输出已截断，仅显示前 ").append(maxChars).append(" 字符]");
        }
        if (sb.length() == 0) {
            sb.append("(无输出)");
        }
        return sb.toString();
    }

    /**
     * 异步排空输入流到 StringBuilder：即使达到截断上限也继续消费流，
     * 避免管道缓冲区写满阻塞子进程。
     */
    private Thread drainAsync(InputStream in, StringBuilder target, int maxChars) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (target.length() < maxChars) {
                        int room = maxChars - target.length();
                        target.append(line, 0, Math.min(line.length(), room)).append('\n');
                    }
                }
            } catch (Exception ignored) {
            }
        }, "skill-exec-drain");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void joinQuietly(Thread thread) {
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 空设备（丢弃 stdin）：Windows 为 NUL，其余平台为 /dev/null。
     */
    private File nullDevice() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return new File(os.contains("win") ? "NUL" : "/dev/null");
    }
}