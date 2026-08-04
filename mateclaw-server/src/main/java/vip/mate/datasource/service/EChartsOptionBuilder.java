package vip.mate.datasource.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ECharts Option 自动生成器
 * <p>
 * 根据 SQL 查询结果的列类型和数据特征，自动选择图表类型并生成 ECharts option JSON。
 * 参考 SQLBot 的图表选择规则，使用确定性逻辑代替 LLM 推理。
 * <p>
 * 规则：
 * <ul>
 *   <li>日期/时间列 + 数值列 → 折线图 (line)</li>
 *   <li>分类列 + 数值列（≤15 个类别）→ 柱状图 (bar)</li>
 *   <li>分类列 + 单个数值列（≤8 个类别）→ 饼图 (pie)</li>
 *   <li>其他情况（单行、纯文本、列太多）→ 不生成图表</li>
 * </ul>
 *
 * @author SnSclaw
 */
public class EChartsOptionBuilder {

    private static final int MIN_ROWS = 2;
    private static final int MAX_ROWS_FOR_CHART = 50;
    private static final int MAX_PIE_CATEGORIES = 8;
    private static final int MAX_BAR_CATEGORIES = 15;

    /**
     * 尝试根据查询结果生成 ECharts option JSON 字符串。
     *
     * @param columns 列名列表
     * @param rows    数据行（每行是字符串列表）
     * @return ECharts option JSON 字符串，如果数据不适合可视化则返回 null
     */
    public static String tryBuild(List<String> columns, List<List<String>> rows) {
        if (columns == null || rows == null || rows.size() < MIN_ROWS || rows.size() > MAX_ROWS_FOR_CHART) {
            return null;
        }
        if (columns.size() < 2 || columns.size() > 10) {
            return null;
        }

        // 分析每列的类型
        List<ColumnInfo> colInfos = analyzeColumns(columns, rows);

        // 找出维度列（第一个非数值列）和数值列
        ColumnInfo dimensionCol = null;
        List<ColumnInfo> metricCols = new ArrayList<>();
        for (ColumnInfo ci : colInfos) {
            if (ci.type == ColType.NUMERIC) {
                metricCols.add(ci);
            } else if (dimensionCol == null) {
                dimensionCol = ci;
            }
        }

        // 必须有至少一个维度列和一个数值列
        if (dimensionCol == null || metricCols.isEmpty()) {
            return null;
        }

        int categoryCount = rows.size();

        // 决定图表类型
        if (metricCols.size() == 1 && categoryCount <= MAX_PIE_CATEGORIES && dimensionCol.type == ColType.CATEGORY) {
            return buildPie(dimensionCol, metricCols.get(0), rows, columns);
        } else if (dimensionCol.type == ColType.DATE) {
            return buildLine(dimensionCol, metricCols, rows, columns);
        } else if (categoryCount <= MAX_BAR_CATEGORIES) {
            return buildBar(dimensionCol, metricCols, rows, columns);
        } else {
            return buildLine(dimensionCol, metricCols, rows, columns);
        }
    }

    // ==================== 图表构建 ====================

    private static String buildLine(ColumnInfo dim, List<ColumnInfo> metrics, List<List<String>> rows, List<String> columns) {
        JSONObject option = new JSONObject(new LinkedHashMap<>());
        option.set("title", new JSONObject().set("text", buildTitle(dim, metrics, "趋势")).set("left", "center"));
        option.set("tooltip", new JSONObject().set("trigger", "axis"));

        if (metrics.size() > 1) {
            JSONArray legendData = new JSONArray();
            metrics.forEach(m -> legendData.add(m.name));
            option.set("legend", new JSONObject().set("data", legendData).set("bottom", 0));
        }

        option.set("grid", defaultGrid());
        option.set("xAxis", new JSONObject()
                .set("type", "category")
                .set("data", extractColumn(rows, columns.indexOf(dim.name))));
        option.set("yAxis", new JSONObject().set("type", "value"));

        JSONArray series = new JSONArray();
        for (ColumnInfo m : metrics) {
            series.add(new JSONObject()
                    .set("name", m.name)
                    .set("type", "line")
                    .set("data", extractNumericColumn(rows, columns.indexOf(m.name)))
                    .set("smooth", true));
        }
        option.set("series", series);
        return option.toString();
    }

    private static String buildBar(ColumnInfo dim, List<ColumnInfo> metrics, List<List<String>> rows, List<String> columns) {
        JSONObject option = new JSONObject(new LinkedHashMap<>());
        option.set("title", new JSONObject().set("text", buildTitle(dim, metrics, "对比")).set("left", "center"));
        option.set("tooltip", new JSONObject().set("trigger", "axis"));

        if (metrics.size() > 1) {
            JSONArray legendData = new JSONArray();
            metrics.forEach(m -> legendData.add(m.name));
            option.set("legend", new JSONObject().set("data", legendData).set("bottom", 0));
        }

        option.set("grid", defaultGrid());
        option.set("xAxis", new JSONObject()
                .set("type", "category")
                .set("data", extractColumn(rows, columns.indexOf(dim.name))));
        option.set("yAxis", new JSONObject().set("type", "value"));

        JSONArray series = new JSONArray();
        for (ColumnInfo m : metrics) {
            series.add(new JSONObject()
                    .set("name", m.name)
                    .set("type", "bar")
                    .set("data", extractNumericColumn(rows, columns.indexOf(m.name))));
        }
        option.set("series", series);
        return option.toString();
    }

    private static String buildPie(ColumnInfo dim, ColumnInfo metric, List<List<String>> rows, List<String> columns) {
        JSONObject option = new JSONObject(new LinkedHashMap<>());
        option.set("title", new JSONObject().set("text", buildTitle(dim, List.of(metric), "占比")).set("left", "center"));
        option.set("tooltip", new JSONObject().set("trigger", "item"));

        int dimIdx = columns.indexOf(dim.name);
        int metricIdx = columns.indexOf(metric.name);

        JSONArray pieData = new JSONArray();
        for (List<String> row : rows) {
            JSONObject item = new JSONObject();
            item.set("name", row.get(dimIdx));
            item.set("value", parseNumber(row.get(metricIdx)));
            pieData.add(item);
        }

        JSONArray series = new JSONArray();
        series.add(new JSONObject()
                .set("name", metric.name)
                .set("type", "pie")
                .set("radius", "60%")
                .set("data", pieData));
        option.set("series", series);
        return option.toString();
    }

    // ==================== 列分析 ====================

    private enum ColType { NUMERIC, DATE, CATEGORY }

    private static class ColumnInfo {
        String name;
        ColType type;

        ColumnInfo(String name, ColType type) {
            this.name = name;
            this.type = type;
        }
    }

    private static List<ColumnInfo> analyzeColumns(List<String> columns, List<List<String>> rows) {
        List<ColumnInfo> result = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            String colName = columns.get(i);
            ColType type = detectColumnType(colName, rows, i);
            result.add(new ColumnInfo(colName, type));
        }
        return result;
    }

    private static ColType detectColumnType(String colName, List<List<String>> rows, int colIdx) {
        // 列名启发式判断日期
        String lowerName = colName.toLowerCase();
        if (lowerName.contains("date") || lowerName.contains("time") || lowerName.contains("day")
                || lowerName.contains("month") || lowerName.contains("year") || lowerName.contains("week")
                || lowerName.contains("日期") || lowerName.contains("时间") || lowerName.contains("月份")) {
            return ColType.DATE;
        }

        // 采样数据判断类型
        int numericCount = 0;
        int dateCount = 0;
        int sampleSize = Math.min(rows.size(), 10);
        for (int i = 0; i < sampleSize; i++) {
            String val = rows.get(i).get(colIdx);
            if (val == null || "NULL".equals(val)) continue;
            if (isNumeric(val)) {
                numericCount++;
            } else if (isDateLike(val)) {
                dateCount++;
            }
        }

        if (numericCount >= sampleSize * 0.8) return ColType.NUMERIC;
        if (dateCount >= sampleSize * 0.5) return ColType.DATE;
        return ColType.CATEGORY;
    }

    private static boolean isNumeric(String val) {
        if (val == null || val.isEmpty()) return false;
        try {
            new BigDecimal(val.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isDateLike(String val) {
        if (val == null || val.length() < 6) return false;
        // 匹配常见日期格式：2024-01-01, 2024/01/01, 2024-01, 01-01, 20240101 等
        return val.matches("\\d{4}[-/]\\d{1,2}([-/]\\d{1,2})?.*")
                || val.matches("\\d{1,2}[-/]\\d{1,2}([-/]\\d{2,4})?");
    }

    // ==================== 工具方法 ====================

    private static JSONArray extractColumn(List<List<String>> rows, int idx) {
        JSONArray arr = new JSONArray();
        for (List<String> row : rows) {
            arr.add(row.get(idx));
        }
        return arr;
    }

    private static JSONArray extractNumericColumn(List<List<String>> rows, int idx) {
        JSONArray arr = new JSONArray();
        for (List<String> row : rows) {
            arr.add(parseNumber(row.get(idx)));
        }
        return arr;
    }

    private static Object parseNumber(String val) {
        if (val == null || "NULL".equals(val)) return 0;
        try {
            BigDecimal bd = new BigDecimal(val.trim());
            // 如果没有小数部分，返回整数
            if (bd.scale() <= 0 || bd.stripTrailingZeros().scale() <= 0) {
                return bd.longValue();
            }
            return bd.doubleValue();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String buildTitle(ColumnInfo dim, List<ColumnInfo> metrics, String suffix) {
        if (metrics.size() == 1) {
            return metrics.get(0).name + " " + suffix;
        }
        return dim.name + " " + suffix;
    }

    private static JSONObject defaultGrid() {
        return new JSONObject()
                .set("left", "3%")
                .set("right", "4%")
                .set("bottom", "12%")
                .set("containLabel", true);
    }
}
