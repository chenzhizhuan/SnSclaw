package vip.mate.datasource.service;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.statement.select.Limit;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;

/**
 * SQL 安全验证服务
 * <p>
 * 仅允许 SELECT 语句，拒绝一切写操作。
 * 无 LIMIT 时自动注入 LIMIT 500。
 *
 * @author SnSclaw
 */
@Slf4j
@Service
public class SqlValidationService {

    private static final long DEFAULT_LIMIT = 500;

    /**
     * 验证并处理 SQL：
     * 1. 仅允许单条 SELECT 语句
     * 2. 无 LIMIT 时自动注入 LIMIT 500
     *
     * @param sql 原始 SQL
     * @return 处理后的安全 SQL
     */
    public String validateAndNormalize(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new MateClawException("err.datasource.sql_empty", "SQL 不能为空");
        }

        // 去除末尾分号
        sql = sql.strip();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).strip();
        }

        Statement statement;
        try {
            // 尝试解析为多条语句，检查是否有多语句注入
            Statements stmts = CCJSqlParserUtil.parseStatements(sql);
            if (stmts.getStatements().size() != 1) {
                throw new MateClawException("err.datasource.only_single_sql", "仅允许执行单条 SQL 语句，检测到 " + stmts.getStatements().size() + " 条");
            }
            statement = stmts.getStatements().get(0);
        } catch (JSQLParserException e) {
            throw new MateClawException("err.datasource.sql_parse_failed", "SQL 解析失败: " + e.getMessage());
        }

        // 仅允许 SELECT
        if (!(statement instanceof Select)) {
            throw new MateClawException("err.datasource.only_select", "仅允许 SELECT 查询，检测到: " + statement.getClass().getSimpleName());
        }

        Select select = (Select) statement;

        // 注入 LIMIT（如果缺失）
        injectLimitIfAbsent(select);

        return select.toString();
    }

    private void injectLimitIfAbsent(Select select) {
        if (select instanceof PlainSelect) {
            PlainSelect plain = (PlainSelect) select;
            if (plain.getLimit() == null) {
                Limit limit = new Limit();
                limit.setRowCount(new LongValue(DEFAULT_LIMIT));
                plain.setLimit(limit);
            }
        } else if (select instanceof SetOperationList) {
            SetOperationList setOp = (SetOperationList) select;
            if (setOp.getLimit() == null) {
                Limit limit = new Limit();
                limit.setRowCount(new LongValue(DEFAULT_LIMIT));
                setOp.setLimit(limit);
            }
        }
    }
}
