/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 *    Copyright (c) 2026 Payara Foundation and/or its affiliates. All rights reserved.
 *
 *     The contents of this file are subject to the terms of either the GNU
 *     General Public License Version 2 only ("GPL") or the Common Development
 *     and Distribution License("CDDL") (collectively, the "License").  You
 *     may not use this file except in compliance with the License.  You can
 *     obtain a copy of the License at
 *     https://github.com/payara/Payara/blob/main/LICENSE.txt
 *     See the License for the specific
 *     language governing permissions and limitations under the License.
 *
 *     When distributing the software, include this License Header Notice in each
 *     file and include the License file at legal/OPEN-SOURCE-LICENSE.txt.
 *
 *     GPL Classpath Exception:
 *     The Payara Foundation designates this particular file as subject to the "Classpath"
 *     exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 *     file that accompanied this code.
 *
 *     Modifications:
 *     If applicable, add the following below the License Header, with the fields
 *     enclosed by brackets [] replaced by your own identifying information:
 *     "Portions Copyright [year] [name of copyright owner]"
 *
 *     Contributor(s):
 *     If you wish your version of this file to be governed by only the CDDL or
 *     only the GPL Version 2, indicate your decision by adding "[Contributor]
 *     elects to include this software in this distribution under the [CDDL or GPL
 *     Version 2] license."  If you don't indicate a single choice of license, a
 *     recipient has the option to distribute your version of this file under
 *     either the CDDL, the GPL Version 2 or to extend the choice of license to
 *     its licensees as provided above.  However, if you add GPL Version 2 code
 *     and therefore, elected the GPL Version 2 license, then the option applies
 *     only if the new code is made subject to such option by the copyright
 *     holder.
 */
package fish.payara.data.core.util;

import fish.payara.data.core.cdi.extension.EntityMetadata;
import fish.payara.data.core.cdi.extension.QueryData;
import fish.payara.data.core.cdi.extension.QueryMetadata;
import fish.payara.data.core.cdi.extension.QueryType;
import fish.payara.data.core.querymethod.QueryMethodParser;
import jakarta.data.page.CursoredPage;
import jakarta.data.page.Page;
import jakarta.data.page.PageRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Checks that a derived query returning {@link Page} counts its total with a COUNT query
 * instead of re-running the select query.
 */
public class QueryByNameOperationUtilityPageTest {

    public static class Book {
        String title;
    }

    public interface Books {
        Page<Book> findByTitleLike(String pattern, PageRequest pageRequest);

        CursoredPage<Book> findByTitleLikeOrderByTitle(String pattern, PageRequest pageRequest);
    }

    @Test
    public void derivedPageTotalElementsWithSingleMatch() throws Exception {
        List<String> countQueries = new ArrayList<>();
        // the select query returns one entity, a COUNT query returns its count
        EntityManager em = fakeEntityManager(countQueries, List.of(new Book()));

        Page<?> page = findByTitleLike(em, PageRequest.ofSize(10));

        assertEquals(1, page.numberOfElements());
        assertEquals(1L, page.totalElements());
        assertEquals(1, countQueries.size());
        assertTrue("count query must be a COUNT query: " + countQueries.get(0),
                countQueries.get(0).startsWith("SELECT COUNT("));
        assertTrue("count query must not be ordered: " + countQueries.get(0),
                !countQueries.get(0).contains("ORDER BY"));
    }

    @Test
    public void derivedPageTotalElementsWithSeveralMatchesIsNotTheLoadedPageSize() throws Exception {
        List<String> countQueries = new ArrayList<>();
        // the page holds 2 of 5 matching entities (plus one look-ahead row); the COUNT query returns 5
        EntityManager em = fakeEntityManager(countQueries, List.of(new Book(), new Book(), new Book()), 5L);

        Page<?> page = findByTitleLike(em, PageRequest.ofSize(2));

        assertEquals(2, page.numberOfElements());
        assertEquals(5L, page.totalElements());
        assertEquals(3L, page.totalPages());
    }

    @Test
    public void derivedCursoredPageTotalElementsWithSingleMatch() throws Exception {
        List<String> countQueries = new ArrayList<>();
        EntityManager em = fakeEntityManager(countQueries, List.of(new Book()));

        Page<?> page = find(em, "findByTitleLikeOrderByTitle", PageRequest.ofSize(10));

        assertTrue(page instanceof CursoredPage);
        assertEquals(1, page.numberOfElements());
        assertEquals(1L, page.totalElements());
        assertEquals(1, countQueries.size());
        assertTrue("count query must be a COUNT query: " + countQueries.get(0),
                countQueries.get(0).startsWith("SELECT COUNT("));
    }

    private static Page<?> findByTitleLike(EntityManager em, PageRequest pageRequest) throws Exception {
        return find(em, "findByTitleLike", pageRequest);
    }

    private static Page<?> find(EntityManager em, String methodName, PageRequest pageRequest) throws Exception {
        Method method = Books.class.getMethod(methodName, String.class, PageRequest.class);
        QueryMethodParser.ParseResult parseResult = new QueryMethodParser(method.getName()).parse();
        EntityMetadata entityMetadata = new EntityMetadata("Book", Book.class, Map.of("title", "title"),
                Map.of("title", String.class), Collections.emptyMap(), Long.class);
        QueryMetadata queryMetadata = new QueryMetadata(Books.class, method, Book.class, null,
                QueryType.FIND_BY_NAME, entityMetadata, parseResult);
        Object result = QueryByNameOperationUtility.processFindByNameOperation(
                new Object[]{"%Beta%", pageRequest}, new QueryData(queryMetadata), em);
        return (Page<?>) result;
    }

    private static EntityManager fakeEntityManager(List<String> countQueries, List<?> rows) {
        return fakeEntityManager(countQueries, rows, (long) rows.size());
    }

    private static EntityManager fakeEntityManager(List<String> countQueries, List<?> rows, long count) {
        EntityType<?> entityType = proxy(EntityType.class, (name, args) -> null);
        Metamodel metamodel = proxy(Metamodel.class, (name, args) -> "entity".equals(name) ? entityType : null);
        return proxy(EntityManager.class, (name, args) -> {
            switch (name) {
                case "getMetamodel":
                    return metamodel;
                case "createQuery":
                    String jpql = (String) args[0];
                    boolean isCount = jpql.startsWith("SELECT COUNT(");
                    if (args.length > 1 && Long.class.equals(args[1])) {
                        countQueries.add(jpql);
                    }
                    return fakeQuery(isCount ? List.of(count) : rows);
                default:
                    throw new UnsupportedOperationException(name);
            }
        });
    }

    private static TypedQuery<?> fakeQuery(List<?> resultList) {
        TypedQuery<?>[] self = new TypedQuery<?>[1];
        self[0] = proxy(TypedQuery.class, (name, args) -> {
            switch (name) {
                case "getResultList":
                    return resultList;
                case "getSingleResult":
                    return resultList.get(0);
                case "setParameter":
                case "setFirstResult":
                case "setMaxResults":
                    return self[0];
                default:
                    throw new UnsupportedOperationException(name);
            }
        });
        return self[0];
    }

    private interface Handler {
        Object invoke(String methodName, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(QueryByNameOperationUtilityPageTest.class.getClassLoader(),
                new Class<?>[]{type}, (p, m, a) -> handler.invoke(m.getName(), a == null ? new Object[0] : a));
    }
}
