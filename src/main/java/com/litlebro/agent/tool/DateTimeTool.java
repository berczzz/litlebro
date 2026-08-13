package com.litlebro.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 日期时间工具，供 LLM 通过 Function Calling 调用。
 *
 * <p>提供以下日期时间相关能力：
 * <ul>
 *   <li>获取当前日期时间（精确到秒，含时区）</li>
 *   <li>获取当前日期（不含时间）</li>
 *   <li>根据日期判断星期几</li>
 *   <li>计算两个日期之间的天数差</li>
 *   <li>根据基准日期和偏移天数计算目标日期</li>
 * </ul>
 *
 * <p>每个方法使用 @Tool 注解标注，Spring AI 会自动将其注册为 LLM 可调用的函数。
 * 方法的 @ToolParam 注解提供参数描述，帮助 LLM 理解参数含义。
 *
 * <p>实现 {@link AgentTool} 接口，提供工具名称和描述等元数据，
 * 供 {@link ToolRegistry} 统一注册与权限控制。
 *
 * <p>使用 Java 8+ 的 java.time API 而非传统的 Date/Calendar，
 * 因为 java.time 是不可变且线程安全的，API 更清晰直观。
 */
@Component
public class DateTimeTool implements AgentTool {

    @Override
    public String name() {
        return "日期时间工具";
    }

    @Override
    public String description() {
        return "获取当前时间、判断星期几、计算日期差、日期偏移";
    }

    /** 日期格式化器，统一输出 yyyy-MM-dd 格式 */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 日期时间格式化器，统一输出 yyyy-MM-dd HH:mm:ss 格式 */
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 获取当前系统日期和时间，精确到秒，同时返回所在时区。
     * 时区信息帮助 LLM 在不同地区使用时正确理解时间。
     *
     * @return 格式化的当前日期时间字符串，含时区
     */
    @Tool(description = "获取当前系统日期和时间，精确到秒，同时返回所在时区")
    public String getCurrentDateTime() {
        return "当前时间: " + LocalDateTime.now().format(DATETIME_FORMATTER)
                + " (时区: " + ZoneId.systemDefault() + ")";
    }

    /**
     * 获取当前系统日期，不含时间部分。
     * 适用于只需要日期、不需要具体时刻的场景。
     *
     * @return 格式化的当前日期字符串
     */
    @Tool(description = "获取当前系统日期，不含时间部分")
    public String getCurrentDate() {
        return "当前日期: " + LocalDate.now().format(DATE_FORMATTER);
    }

    /**
     * 根据给定的日期字符串，判断该日期是星期几。
     * 使用 switch 表达式将 DayOfWeek 枚举映射为中文星期名称。
     *
     * @param dateStr 日期字符串，格式必须为 yyyy-MM-dd
     * @return 中文星期名称，如 "2026-08-11 是 星期二"
     */
    @Tool(description = "根据给定的日期字符串，判断该日期是星期几")
    public String getDayOfWeek(
            @ToolParam(description = "日期字符串，格式为 yyyy-MM-dd，例如 2026-08-11") String dateStr) {
        try {
            LocalDate date = LocalDate.parse(dateStr, DATE_FORMATTER);
            DayOfWeek dayOfWeek = date.getDayOfWeek();
            // 将英文 DayOfWeek 枚举映射为中文星期名称
            String chineseDay = switch (dayOfWeek) {
                case MONDAY -> "星期一";
                case TUESDAY -> "星期二";
                case WEDNESDAY -> "星期三";
                case THURSDAY -> "星期四";
                case FRIDAY -> "星期五";
                case SATURDAY -> "星期六";
                case SUNDAY -> "星期日";
            };
            return dateStr + " 是 " + chineseDay;
        } catch (Exception e) {
            return "日期格式错误，请使用 yyyy-MM-dd 格式，例如 2026-08-11，当前输入: " + dateStr;
        }
    }

    /**
     * 计算两个日期之间相差的天数，返回绝对值。
     * 使用 ChronoUnit.DAYS.between 计算，语义清晰且性能好。
     *
     * @param dateStr1 第一个日期，格式 yyyy-MM-dd
     * @param dateStr2 第二个日期，格式 yyyy-MM-dd
     * @return 两个日期相差的天数
     */
    @Tool(description = "计算两个日期之间相差的天数，返回绝对值")
    public String daysBetween(
            @ToolParam(description = "第一个日期，格式 yyyy-MM-dd") String dateStr1,
            @ToolParam(description = "第二个日期，格式 yyyy-MM-dd") String dateStr2) {
        try {
            LocalDate d1 = LocalDate.parse(dateStr1, DATE_FORMATTER);
            LocalDate d2 = LocalDate.parse(dateStr2, DATE_FORMATTER);
            // 使用 ChronoUnit 计算精确天数差，Math.abs 确保返回非负值
            long days = Math.abs(ChronoUnit.DAYS.between(d1, d2));
            return dateStr1 + " 和 " + dateStr2 + " 之间相差 " + days + " 天";
        } catch (Exception e) {
            return "日期格式错误，请使用 yyyy-MM-dd 格式，例如 2026-08-11，当前输入: " + dateStr1 + ", " + dateStr2;
        }
    }

    /**
     * 根据给定的基准日期和偏移天数，计算偏移后的日期。
     * 正数偏移表示未来，负数偏移表示过去。
     *
     * @param dateStr 基准日期，格式 yyyy-MM-dd
     * @param days    偏移天数，正数表示未来，负数表示过去
     * @return 偏移后的日期字符串
     */
    @Tool(description = "根据给定的日期和偏移天数，计算偏移后的日期。正数表示未来，负数表示过去")
    public String addDays(
            @ToolParam(description = "基准日期，格式 yyyy-MM-dd") String dateStr,
            @ToolParam(description = "偏移天数，正数表示未来，负数表示过去") int days) {
        try {
            LocalDate date = LocalDate.parse(dateStr, DATE_FORMATTER);
            LocalDate result = date.plusDays(days);
            String direction = days >= 0 ? "后" : "前";
            return dateStr + " " + Math.abs(days) + " 天" + direction + "是 " + result.format(DATE_FORMATTER);
        } catch (Exception e) {
            return "日期格式错误，请使用 yyyy-MM-dd 格式，例如 2026-08-11，当前输入: " + dateStr;
        }
    }
}