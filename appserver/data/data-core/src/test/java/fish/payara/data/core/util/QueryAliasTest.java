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

import fish.payara.data.core.cdi.extension.EntityMetadata;
import fish.payara.data.core.cdi.extension.QueryData;
import fish.payara.data.core.cdi.extension.QueryMetadata;
import fish.payara.data.core.cdi.extension.QueryType;
import jakarta.data.Sort;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Tests that the identification variable of a {@code @Query} (for example {@code select b from Book b ...}) is used
 * for a runtime sort and kept in the count query of a page.
 */
public class QueryAliasTest {

    @Test
    public void testRootAlias() {
        assertEquals("b", FindOperationUtility.getRootAlias("SELECT b FROM Book b WHERE lower(b.title) like ?1"));
        assertEquals("b", FindOperationUtility.getRootAlias("select b from Book as b where b.id > 1"));
        assertEquals("b", FindOperationUtility.getRootAlias("SELECT b FROM Book b"));
        assertEquals("b", FindOperationUtility.getRootAlias("SELECT b FROM Book b ORDER BY b.title"));
        assertEquals("b", FindOperationUtility.getRootAlias("SELECT b FROM Book b JOIN b.author a WHERE a.name = ?1"));
        assertEquals("b", FindOperationUtility.getRootAlias("SELECT b FROM Book b, Author a WHERE b.author = a"));
        assertNull(FindOperationUtility.getRootAlias("FROM Book WHERE lower(title) like ?1"));
        assertNull(FindOperationUtility.getRootAlias("FROM Book"));
        assertNull(FindOperationUtility.getRootAlias("FROM Book ORDER BY title"));
        assertNull(FindOperationUtility.getRootAlias("FROM Book JOIN author a WHERE a.name = ?1"));
        assertNull(FindOperationUtility.getRootAlias("WHERE lower(title) like ?1"));
    }

    @Test
    public void testRootAliasOfProcessedQuery() {
        assertEquals("b", FindOperationUtility.getRootAlias(process("select b from Book b where lower(b.title) like ?1")));
        assertNull(FindOperationUtility.getRootAlias(process("from Book where lower(title) like ?1")));
    }

    @Test
    public void testSortUsesRootAlias() {
        String query = process("select b from Book b where lower(b.title) like ?1");
        StringBuilder sorted = new StringBuilder(query);
        DataCommonOperationUtility.handleSort(queryData(query), List.of(Sort.asc("title"), Sort.descIgnoreCase("author")),
                sorted, false, false, false, FindOperationUtility.getRootAlias(query));
        assertEquals(query + " ORDER BY b.title ASC, LOWER(b.author) DESC", sorted.toString());
    }

    @Test
    public void testSortWithoutAlias() {
        String query = process("from Book where lower(title) like ?1");
        StringBuilder sorted = new StringBuilder(query);
        DataCommonOperationUtility.handleSort(queryData(query), List.of(Sort.ascIgnoreCase("title")),
                sorted, false, false, false, FindOperationUtility.getRootAlias(query));
        assertEquals(query + " ORDER BY LOWER(title) ASC", sorted.toString());
    }

    @Test
    public void testCountQueryKeepsAlias() {
        assertEquals("SELECT COUNT(b) FROM Book b WHERE lower(b.title) like ?1",
                countQuery(process("select b from Book b where lower(b.title) like ?1"), true, false));
        assertEquals("SELECT COUNT(b) FROM Book AS b WHERE b.id > ?1",
                countQuery("SELECT b FROM Book AS b WHERE b.id > ?1", true, false));
        assertEquals("SELECT COUNT(b) FROM Book b",
                countQuery("SELECT b FROM Book b", false, false));
    }

    @Test
    public void testCountQueryKeepsJoins() {
        assertEquals("SELECT COUNT(b) FROM Book b JOIN b.author a WHERE a.name = ?1",
                countQuery("SELECT b FROM Book b JOIN b.author a WHERE a.name = ?1", true, false));
        assertEquals("SELECT COUNT(DISTINCT b) FROM Book b LEFT JOIN b.author a WHERE a.name = ?1",
                countQuery("SELECT DISTINCT b FROM Book b LEFT JOIN FETCH b.author a WHERE a.name = ?1 ORDER BY b.title",
                        true, true));
    }

    @Test
    public void testCountQueryWithoutAlias() {
        assertEquals("SELECT COUNT(this) FROM Book WHERE lower(title) like ?1",
                countQuery(process("from Book where lower(title) like ?1"), true, false));
    }

    private static String process(String query) {
        return QueryOperationUtility.processQuery(query,
                QueryOperationUtility.preprocessQueryString(query, List.of("SELECT", "FROM", "WHERE", "ORDER", "GROUP", "BY", "HAVING")),
                Book.class).getKey();
    }

    private static String countQuery(String query, boolean hasWhere, boolean hasOrder) {
        QueryData queryData = queryData(query);
        FindOperationUtility.createCountQuery(queryData, hasWhere, hasOrder, query.indexOf("WHERE"), query.indexOf("ORDER"));
        // the WHERE part is copied verbatim, normalize the whitespace
        return queryData.getCountQueryString().replaceAll("\\s+", " ").trim();
    }

    private static QueryData queryData(String query) {
        EntityMetadata entityMetadata = new EntityMetadata("Book", Book.class,
                Map.of("title", "title", "author", "author", "id", "id"), Map.of(), Map.of(), Long.class);
        QueryMetadata queryMetadata = new QueryMetadata(null, null, Book.class, null, QueryType.QUERY, entityMetadata, null);
        QueryData queryData = new QueryData(queryMetadata);
        queryData.setQueryString(query);
        return queryData;
    }
}

/**
 * Entity stand-in, top level so that its simple name is used as the entity name.
 */
class Book {
}
