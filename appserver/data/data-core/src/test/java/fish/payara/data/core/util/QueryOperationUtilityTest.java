/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2026 Payara Foundation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/main/LICENSE.txt
 * See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at legal/OPEN-SOURCE-LICENSE.txt.
 *
 * GPL Classpath Exception:
 * The Payara Foundation designates this particular file as subject to the "Classpath"
 * exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package fish.payara.data.core.util;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * Tests for the rewriting of {@code @Query} strings by {@link QueryOperationUtility#processQuery}.
 */
public class QueryOperationUtilityTest {

    private static Map.Entry<String, Set<String>> process(String query, List<String> patterns) {
        Map<Integer, String> positions = QueryOperationUtility.preprocessQueryString(query, patterns);
        return QueryOperationUtility.processQuery(query, positions, Book.class);
    }

    private static String select(String query) {
        return normalize(process(query, QueryOperationUtility.selectQueryPatterns).getKey());
    }

    private static String normalize(String query) {
        return query.trim().replaceAll("\\s+", " ");
    }

    @Test
    public void staticOrderByWithoutWhere() {
        assertEquals("SELECT b FROM Book b ORDER BY b.title", select("select b from Book b order by b.title"));
    }

    @Test
    public void staticOrderByWithoutWhereUpperCase() {
        assertEquals("SELECT b FROM Book b ORDER BY b.title DESC", select("SELECT b FROM Book b ORDER BY b.title DESC"));
    }

    @Test
    public void staticOrderByWithoutWhereMixedCaseAndWhitespace() {
        assertEquals("SELECT b FROM Book b ORDER BY b.title, b.id",
                select("Select b\n  From Book b\n  Order   By b.title, b.id"));
    }

    @Test
    public void projectionWithStaticOrderByWithoutWhere() {
        assertEquals("SELECT b.title FROM Book b ORDER BY b.title", select("select b.title from Book b order by b.title"));
    }

    @Test
    public void staticOrderByWithWhere() {
        assertEquals("SELECT b FROM Book b WHERE b.id > 0 ORDER BY b.title",
                select("select b from Book b where b.id > 0 order by b.title"));
    }

    @Test
    public void selectWithAliasOnly() {
        assertEquals("SELECT b FROM Book b", select("select b from Book b"));
    }

    @Test
    public void fromWithoutAliasAndOrderBy() {
        assertEquals("FROM Book ORDER BY title", select("from Book order by title"));
    }

    @Test
    public void fromWithWhere() {
        assertEquals("FROM Book WHERE lower(title) like ?1", select("from Book where lower(title) like ?1"));
    }

    @Test
    public void groupByWithoutWhere() {
        assertEquals("SELECT b.title, count(b) FROM Book b GROUP BY b.title",
                select("select b.title, count(b) from Book b group by b.title"));
    }

    @Test
    public void groupByHavingOrderByWithoutWhere() {
        assertEquals("SELECT b.title, count(b) FROM Book b GROUP BY b.title HAVING count(b) > 1 ORDER BY b.title",
                select("select b.title, count(b) from Book b group by b.title having count(b) > 1 order by b.title"));
    }

    @Test
    public void groupByHavingWithWhere() {
        assertEquals("SELECT b.title, count(b) FROM Book b WHERE b.id > 0 GROUP BY b.title HAVING count(b) > 1",
                select("SELECT b.title, count(b) FROM Book b WHERE b.id > 0 GROUP BY b.title HAVING count(b) > 1"));
    }

    @Test
    public void whereOnly() {
        assertEquals("FROM Book WHERE title = :title ORDER BY title", select("where title = :title order by title"));
    }

    @Test
    public void orderByOnly() {
        assertEquals("FROM Book ORDER BY title", select("order by title"));
    }

    @Test
    public void selectWithoutFrom() {
        assertEquals("SELECT title FROM Book WHERE id = ?1 ORDER BY title",
                select("select title where id = ?1 order by title"));
        assertEquals("SELECT title FROM Book ORDER BY title", select("select title order by title"));
    }

    @Test
    public void keywordsInsideIdentifiersParametersAndLiterals() {
        Map.Entry<String, Set<String>> result = process(
                "select o from Orders o where o.orderDate > :from and o.note <> 'from where order by' order by o.byline",
                QueryOperationUtility.selectQueryPatterns);
        assertEquals("SELECT o FROM Orders o WHERE o.orderDate > :from and o.note <> 'from where order by' ORDER BY o.byline",
                normalize(result.getKey()));
        assertEquals(Set.of("from"), result.getValue());
    }

    @Test
    public void deleteQuery() {
        assertEquals("DELETE FROM Book b WHERE b.id in ?1",
                normalize(process("delete from Book b where b.id in ?1", QueryOperationUtility.deleteQueryPatterns).getKey()));
    }

    @Test
    public void updateQuery() {
        assertEquals("UPDATE Book SET title = :title WHERE id = :id",
                normalize(process("update Book set title = :title where id = :id", QueryOperationUtility.updateQueryPatterns).getKey()));
    }
}

/**
 * Entity class used for the implicit FROM clause (top level, so its simple name is "Book").
 */
class Book {
}
