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
import jakarta.data.repository.By;
import jakarta.data.repository.Delete;
import jakarta.data.repository.Query;
import jakarta.persistence.Cache;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests that {@code @Delete} with {@code @By} and {@code @Query} update/delete operations, which run
 * a bulk {@code executeUpdate}, do not clear a persistence context shared with the caller's
 * transaction, while still evicting the entity type from the second-level cache.
 */
public class BulkOperationPersistenceContextTest {

    public static class Book {
        Long id;
        String title;
    }

    interface BookRepository {
        @Delete
        int removeByTitle(@By("title") String title);

        @Query("delete from Book where title = ?1")
        long deleteTitled(String title);

        @Query("update Book set title = ?2 where title = ?1")
        int retitle(String oldTitle, String newTitle);
    }

    /**
     * Records what the operation does to the persistence context: {@code clear} would detach the
     * entities the caller loaded before calling the repository.
     */
    private static class FakePersistenceContext {
        final int rowsAffected;
        final List<Object> evicted = new ArrayList<>();
        boolean callerEntitiesManaged = true;

        FakePersistenceContext(int rowsAffected) {
            this.rowsAffected = rowsAffected;
        }

        EntityManager entityManager() {
            jakarta.persistence.Query query = proxy(jakarta.persistence.Query.class,
                    (m, a) -> "executeUpdate".equals(m.getName()) ? rowsAffected : null);
            Cache cache = proxy(Cache.class, (m, a) -> {
                if ("evict".equals(m.getName())) {
                    evicted.add(a[0]);
                }
                return null;
            });
            EntityManagerFactory factory = proxy(EntityManagerFactory.class,
                    (m, a) -> "getCache".equals(m.getName()) ? cache : null);
            return proxy(EntityManager.class, (m, a) -> switch (m.getName()) {
                case "getEntityManagerFactory" -> factory;
                case "createQuery" -> query;
                case "isJoinedToTransaction" -> true;
                case "clear" -> {
                    callerEntitiesManaged = false;
                    yield null;
                }
                default -> null;
            });
        }
    }

    private interface Handler {
        Object handle(Method method, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(BulkOperationPersistenceContextTest.class.getClassLoader(),
                new Class<?>[]{type}, (p, m, a) -> handler.handle(m, a));
    }

    private static QueryData queryData(Method method, QueryType queryType, boolean callerTransaction) {
        EntityMetadata entityMetadata = new EntityMetadata("Book", Book.class,
                Map.of("id", "id", "title", "title"), Map.of("id", Long.class, "title", String.class),
                Collections.emptyMap(), Long.class);
        QueryMetadata metadata = new QueryMetadata(BookRepository.class, method, Book.class, Book.class,
                queryType, entityMetadata, null);
        QueryData data = new QueryData(metadata);
        // As set up by RepositoryImpl.applyTransactionalSemantics for TxType.REQUIRED
        data.setLifecycleManagedExternally(true);
        data.setUserTransaction(callerTransaction);
        return data;
    }

    private static TransactionManager activeTransaction() {
        return proxy(TransactionManager.class, (m, a) -> "getStatus".equals(m.getName()) ? Status.STATUS_ACTIVE : null);
    }

    private static FakePersistenceContext deleteBy(boolean callerTransaction) throws Exception {
        Method method = BookRepository.class.getDeclaredMethod("removeByTitle", String.class);
        FakePersistenceContext pc = new FakePersistenceContext(1);
        int rows = DeleteOperationUtility.processDeleteByOperation(new Object[]{"Alpha"}, Book.class,
                activeTransaction(), pc.entityManager(), queryData(method, QueryType.DELETE, callerTransaction), method);
        assertEquals(1, rows);
        return pc;
    }

    private static FakePersistenceContext query(String methodName, Object[] args, Object expectedResult,
                                                boolean callerTransaction) throws Exception {
        Method method = null;
        for (Method m : BookRepository.class.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                method = m;
            }
        }
        FakePersistenceContext pc = new FakePersistenceContext(2);
        Object result = QueryOperationUtility.processQueryOperation(args,
                queryData(method, QueryType.QUERY, callerTransaction), pc.entityManager(), activeTransaction(),
                new DataParameter(null, Collections.emptyList()));
        assertEquals(expectedResult, result);
        return pc;
    }

    @Test
    public void deleteByInCallerTransactionKeepsCallerEntitiesManaged() throws Exception {
        FakePersistenceContext pc = deleteBy(true);

        assertTrue("the persistence context shared with the caller must not be cleared", pc.callerEntitiesManaged);
        assertEquals(List.of(Book.class), pc.evicted);
    }

    @Test
    public void deleteByInOwnTransactionClearsOwnPersistenceContext() throws Exception {
        FakePersistenceContext pc = deleteBy(false);

        assertFalse(pc.callerEntitiesManaged);
        assertEquals(List.of(Book.class), pc.evicted);
    }

    @Test
    public void queryDeleteInCallerTransactionKeepsCallerEntitiesManaged() throws Exception {
        FakePersistenceContext pc = query("deleteTitled", new Object[]{"Alpha"}, 2L, true);

        assertTrue("the persistence context shared with the caller must not be cleared", pc.callerEntitiesManaged);
        assertEquals(List.of(Book.class), pc.evicted);
    }

    @Test
    public void queryUpdateInCallerTransactionKeepsCallerEntitiesManaged() throws Exception {
        FakePersistenceContext pc = query("retitle", new Object[]{"Alpha", "Omega"}, 2, true);

        assertTrue("the persistence context shared with the caller must not be cleared", pc.callerEntitiesManaged);
        assertEquals(List.of(Book.class), pc.evicted);
    }

    @Test
    public void queryDeleteInOwnTransactionClearsOwnPersistenceContext() throws Exception {
        FakePersistenceContext pc = query("deleteTitled", new Object[]{"Alpha"}, 2L, false);

        assertFalse(pc.callerEntitiesManaged);
        assertEquals(List.of(Book.class), pc.evicted);
    }
}
