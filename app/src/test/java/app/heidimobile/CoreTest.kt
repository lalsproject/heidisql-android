package app.heidimobile

import app.heidimobile.db.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlSplitterTest {
    private fun split(s: String, e: Engine = Engine.MYSQL) = SqlSplitter.split(s, e)

    @Test fun splitsOnSemicolonAndDropsEmpty() {
        assertEquals(listOf("SELECT 1", "SELECT 2"), split("SELECT 1;; SELECT 2;\n"))
    }

    @Test fun ignoresSemicolonInsideStringsAndIdentifiers() {
        assertEquals(listOf("SELECT 'a;b', \"c;d\", `e;f`"), split("SELECT 'a;b', \"c;d\", `e;f`;"))
    }

    @Test fun mysqlBackslashEscapeKeepsStringOpen() {
        assertEquals(listOf("SELECT 'it\\'s;ok'"), split("SELECT 'it\\'s;ok';", Engine.MYSQL))
    }

    @Test fun postgresBackslashIsLiteral() {
        // In PostgreSQL 'C:\' is a complete string, so the semicolon after it terminates the statement.
        assertEquals(listOf("SELECT 'C:\\'", "SELECT 2"), split("SELECT 'C:\\'; SELECT 2", Engine.POSTGRES))
    }

    @Test fun doubledQuoteIsEscape() {
        assertEquals(listOf("SELECT 'O''Neil;x'"), split("SELECT 'O''Neil;x'", Engine.SQLITE))
    }

    @Test fun commentsAreKeptButCommentOnlyStatementsDropped() {
        val r = split("-- hello;\nSELECT 1; /* only comment; */ ; # note\nSELECT 2")
        assertEquals(2, r.size)
        assertTrue(r[0].endsWith("SELECT 1"))
        assertTrue(r[1].endsWith("SELECT 2"))
    }

    @Test fun mysqlDashDashNeedsWhitespace() {
        // "--1" is an expression in MySQL (minus minus 1), not a comment
        assertEquals(listOf("SELECT 5--1"), split("SELECT 5--1;"))
        // a ";" inside a line comment does not terminate the statement
        assertEquals(listOf("SELECT 5 -- c;"), split("SELECT 5 -- c;\n"))
        assertEquals(listOf("SELECT 5 -- c"), split("SELECT 5 -- c\n;"))
    }

    @Test fun mysqlDelimiterCommand() {
        val script = "DELIMITER //\nCREATE PROCEDURE p() BEGIN SELECT 1; SELECT 2; END//\nDELIMITER ;\nSELECT 3;"
        val r = split(script)
        assertEquals(2, r.size)
        assertEquals("CREATE PROCEDURE p() BEGIN SELECT 1; SELECT 2; END", r[0])
        assertEquals("SELECT 3", r[1])
    }

    @Test fun postgresDollarQuoting() {
        val r = split("CREATE FUNCTION f() RETURNS int AS \$body\$ SELECT 1; SELECT 2; \$body\$ LANGUAGE sql; SELECT 9", Engine.POSTGRES)
        assertEquals(2, r.size)
        assertTrue(r[0].contains("SELECT 1; SELECT 2;"))
        assertEquals("SELECT 9", r[1])
    }

    @Test fun postgresParameterPlaceholderIsNotDollarQuote() {
        assertEquals(listOf("SELECT \$1", "SELECT 2"), split("SELECT \$1; SELECT 2", Engine.POSTGRES))
    }

    @Test fun mysqlExecutableCommentCountsAsStatement() {
        assertEquals(1, split("/*!40101 SET NAMES utf8 */;").size)
    }

    @Test fun firstKeyword() {
        assertEquals("SELECT", SqlSplitter.firstKeyword("  /* c */ -- x\n select 1"))
        assertEquals("WITH", SqlSplitter.firstKeyword("(with a as (select 1) select * from a)"))
        assertEquals("", SqlSplitter.firstKeyword("   "))
    }
}

class DialectTest {
    @Test fun quoting() {
        assertEquals("`a``b`", Dialect.quoteIdent(Engine.MYSQL, "a`b"))
        assertEquals("\"a\"\"b\"", Dialect.quoteIdent(Engine.POSTGRES, "a\"b"))
        assertEquals("\"main\".\"t\"", Dialect.qualified(Engine.SQLITE, "main", "t"))
    }

    @Test fun literalEscapingDiffersPerEngine() {
        assertEquals("'a\\\\b''c'", Dialect.literal(Engine.MYSQL, "a\\b'c"))
        assertEquals("'a\\b''c'", Dialect.literal(Engine.POSTGRES, "a\\b'c"))
    }

    @Test fun selectPage() {
        assertEquals(
            "SELECT * FROM `d`.`t` WHERE id > 5 ORDER BY `n` DESC LIMIT 1000 OFFSET 2000",
            Dialect.selectPage(Engine.MYSQL, "d", "t", " id > 5; ", "n", true, 1000, 2000)
        )
        assertEquals(
            "SELECT * FROM \"s\".\"t\" LIMIT 10 OFFSET 0",
            Dialect.selectPage(Engine.POSTGRES, "s", "t", "", null, false, 10, 0)
        )
    }

    @Test fun dml() {
        assertEquals("UPDATE `d`.`t` SET `a`=?, `b`=? WHERE `k1`=? AND `k2`=?",
            Dialect.update(Engine.MYSQL, "d", "t", listOf("a", "b"), listOf("k1", "k2")))
        assertEquals("DELETE FROM \"main\".\"t\" WHERE \"id\"=?", Dialect.delete(Engine.SQLITE, "main", "t", listOf("id")))
        assertEquals("INSERT INTO `d`.`t` (`a`) VALUES (?)", Dialect.insert(Engine.MYSQL, "d", "t", listOf("a")))
        assertEquals("INSERT INTO `d`.`t` () VALUES ()", Dialect.insert(Engine.MYSQL, "d", "t", emptyList()))
        assertEquals("INSERT INTO \"s\".\"t\" DEFAULT VALUES", Dialect.insert(Engine.POSTGRES, "s", "t", emptyList()))
    }

    @Test fun truncateAndDrop() {
        assertEquals("DELETE FROM \"main\".\"t\"", Dialect.truncate(Engine.SQLITE, "main", "t"))
        assertEquals("TRUNCATE TABLE `d`.`t`", Dialect.truncate(Engine.MYSQL, "d", "t"))
        assertEquals("DROP VIEW \"s\".\"v\"", Dialect.drop(Engine.POSTGRES, DbObject("s", "v", ObjType.VIEW)))
    }

    @Test fun rowDetection() {
        assertTrue(Dialect.returnsRows("PRAGMA table_info(x)"))
        assertTrue(Dialect.returnsRows("  select 1"))
        assertFalse(Dialect.returnsRows("INSERT INTO t VALUES (1)"))
        assertTrue(Dialect.isDml("/* x */ update t set a=1"))
        assertFalse(Dialect.isDml("CREATE TABLE t(a)"))
    }
}

class CsvAndFormatTest {
    @Test fun csvQuoting() {
        val r = QueryResult(listOf("a", "b,c"), listOf(listOf("x\"y", null), listOf("l1\nl2", "ok")))
        assertEquals("a,\"b,c\"\r\n\"x\"\"y\",\r\n\"l1\nl2\",ok\r\n", Csv.format(r))
        assertEquals("a;b\r\nNULL;1\r\n", Csv.format(QueryResult(listOf("a", "b"), listOf(listOf(null, "1"))), "NULL", ';'))
    }

    @Test fun binaryDisplay() {
        assertEquals("héllo", ValueFormat.bytes("héllo".toByteArray()))
        assertEquals("[BLOB 3 B]", ValueFormat.bytes(byteArrayOf(0, 1, 2)))
        assertEquals("[BLOB 2 B]", ValueFormat.bytes(byteArrayOf(0xC3.toByte(), 0x28)))
        assertEquals(null, ValueFormat.bytes(null))
    }
}

class SqlTokenizerTest {
    private fun kinds(s: String) = SqlTokenizer.tokenize(s).map { s.substring(it.start, it.end) to it.kind }

    @Test fun classifiesTokens() {
        val k = SqlTokenizer.Kind.values()
        val r = kinds("SELECT 'O''Neil;', \"id\", `t`, 12.5 -- c\n/* b */ x")
        assertEquals(
            listOf(
                "SELECT" to SqlTokenizer.Kind.WORD, "'O''Neil;'" to SqlTokenizer.Kind.STRING,
                "\"id\"" to SqlTokenizer.Kind.STRING, "`t`" to SqlTokenizer.Kind.STRING,
                "12.5" to SqlTokenizer.Kind.NUMBER, "-- c" to SqlTokenizer.Kind.COMMENT,
                "/* b */" to SqlTokenizer.Kind.COMMENT, "x" to SqlTokenizer.Kind.WORD
            ), r
        )
        assertEquals(4, k.size)
    }

    @Test fun unterminatedConstructsRunToEnd() {
        assertEquals(listOf("'abc" to SqlTokenizer.Kind.STRING), kinds("'abc"))
        assertEquals(listOf("/* abc" to SqlTokenizer.Kind.COMMENT), kinds("/* abc"))
        assertEquals(listOf("'a\\" to SqlTokenizer.Kind.STRING), kinds("'a\\"))
    }

    @Test fun hugeInputDoesNotOverflowTheStack() {
        val big = "'" + "a\\".repeat(200_000)
        assertEquals(1, SqlTokenizer.tokenize(big).size)
        assertEquals(1, SqlTokenizer.tokenize("/*" + "x".repeat(500_000)).size)
        assertEquals(100_000, SqlTokenizer.tokenize("SELECT 1, ".repeat(50_000)).size)
    }
}
