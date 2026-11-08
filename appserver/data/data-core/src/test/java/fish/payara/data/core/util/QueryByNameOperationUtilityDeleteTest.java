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

import fish.payara.data.core.cdi.extension.QueryData;
import fish.payara.data.core.cdi.extension.QueryMetadata;
import fish.payara.data.core.cdi.extension.QueryType;
import fish.payara.data.core.querymethod.QueryMethodParser;
import jakarta.persistence.Cache;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Query;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests that a derived {@code deleteBy...} operation neither loses its removals nor detaches the
 * caller's entities when the persistence context is shared with a transaction owned by the caller.
 */
public class QueryByNameOperationUtilityDeleteTest {

    public static class Book {
        @Id
        Long id;

        Book(Long id) {
            this.id = id;
        }
    }

    interface BookRepository {
        long deleteByTitle(String title);
    }

    /**
     * Minimal stand-in for a JTA-synchronized persistence context: {@code remove} only queues
     * the removal, {@code flush} writes it, {@code clear} discards whatever was not flushed and
     * detaches everything else (here: {@link #callerEntity}, loaded by the caller beforehand).
     */
    private static class FakePersistenceContext {
        final List<Object> found;
        final List<Object> pendingRemovals = new ArrayList<>();
        final List<Object> flushedRemovals = new ArrayList<>();
        final List<Object> evictedIds = new ArrayList<>();
        final Object callerEntity = new Book(99L);
        boolean callerEntityManaged = true;

        FakePersistenceContext(List<Object> found) {
            this.found = found;
        }

        EntityManager entityManager() {
            EntityType<?> entityType = proxy(EntityType.class, (m, a) -> null);
            Metamodel metamodel = proxy(Metamodel.class, (m, a) -> "entity".equals(m.getName()) ? entityType : null);
            Query query = proxy(Query.class, (m, a) -> "getResultList".equals(m.getName()) ? found : null);
            Cache cache = proxy(Cache.class, (m, a) -> {
                if ("evict".equals(m.getName()) && a.length == 2) {
                    evictedIds.add(a[1]);
                }
                return null;
            });
            EntityManagerFactory factory = proxy(EntityManagerFactory.class,
                    (m, a) -> "getCache".equals(m.getName()) ? cache : null);
            return proxy(EntityManager.class, (m, a) -> switch (m.getName()) {
                case "getMetamodel" -> metamodel;
                case "getEntityManagerFactory" -> factory;
                case "createQuery" -> query;
                case "contains", "isJoinedToTransaction" -> true;
                case "remove" -> {
                    pendingRemovals.add(a[0]);
                    yield null;
                }
                case "flush" -> {
                    flushedRemovals.addAll(pendingRemovals);
                    pendingRemovals.clear();
                    yield null;
                }
                case "clear" -> {
                    pendingRemovals.clear();
                    callerEntityManaged = false;
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
        return (T) Proxy.newProxyInstance(QueryByNameOperationUtilityDeleteTest.class.getClassLoader(),
                new Class<?>[]{type}, (p, m, a) -> handler.handle(m, a));
    }

    private static QueryData queryData(boolean callerTransaction) throws NoSuchMethodException {
        Method method = BookRepository.class.getMethod("deleteByTitle", String.class);
        QueryMethodParser.ParseResult parseResult = new QueryMethodParser.ParseResult(
                QueryMethodParser.Action.DELETE, null, Collections.emptyList(), Collections.emptyList());
        QueryMetadata metadata = new QueryMetadata(BookRepository.class, method, Book.class, Book.class,
                QueryType.DELETE_BY_NAME, null, parseResult);
        QueryData data = new QueryData(metadata);
        // As set up by RepositoryImpl.applyTransactionalSemantics for TxType.REQUIRED
        data.setLifecycleManagedExternally(true);
        data.setUserTransaction(callerTransaction);
        return data;
    }

    private static TransactionManager activeTransaction() {
        return proxy(TransactionManager.class, (m, a) -> "getStatus".equals(m.getName()) ? Status.STATUS_ACTIVE : null);
    }

    @Test
    public void derivedDeleteInCallerTransactionKeepsRemovals() throws Exception {
        FakePersistenceContext pc = new FakePersistenceContext(List.of(new Book(1L), new Book(2L)));

        Object result = QueryByNameOperationUtility.processDeleteByNameOperation(
                new Object[]{"Alpha"}, queryData(true), pc.entityManager(), activeTransaction());

        assertEquals("removals must reach the database before the persistence context is cleared",
                2, pc.flushedRemovals.size());
        assertEquals(2L, result);
    }

    @Test
    public void derivedDeleteInOwnTransactionKeepsRemovals() throws Exception {
        FakePersistenceContext pc = new FakePersistenceContext(List.of(new Book(1L), new Book(2L)));

        Object result = QueryByNameOperationUtility.processDeleteByNameOperation(
                new Object[]{"Alpha"}, queryData(false), pc.entityManager(), activeTransaction());

        assertEquals(2, pc.flushedRemovals.size());
        assertEquals(2L, result);
    }

    @Test
    public void derivedDeleteInCallerTransactionKeepsCallerEntitiesManaged() throws Exception {
        FakePersistenceContext pc = new FakePersistenceContext(List.of(new Book(1L), new Book(2L)));

        QueryByNameOperationUtility.processDeleteByNameOperation(
                new Object[]{"Alpha"}, queryData(true), pc.entityManager(), activeTransaction());

        assertTrue("the persistence context shared with the caller must not be cleared",
                pc.callerEntityManaged);
        assertEquals("the deleted instances must still be evicted from the second-level cache",
                List.of(1L, 2L), pc.evictedIds);
    }

    @Test
    public void derivedDeleteInOwnTransactionClearsOwnPersistenceContext() throws Exception {
        FakePersistenceContext pc = new FakePersistenceContext(List.of(new Book(1L), new Book(2L)));

        QueryByNameOperationUtility.processDeleteByNameOperation(
                new Object[]{"Alpha"}, queryData(false), pc.entityManager(), activeTransaction());

        assertFalse(pc.callerEntityManaged);
        assertEquals(List.of(1L, 2L), pc.evictedIds);
    }
}
