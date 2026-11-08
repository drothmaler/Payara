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
package fish.payara.data.core.cdi.extension;

import fish.payara.data.core.querymethod.QueryMethodParser;
import fish.payara.data.core.querymethod.QueryMethodSyntaxException;
import jakarta.data.repository.Delete;
import jakarta.data.repository.Query;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for the deploy-time "query by method name" classification performed in
 * {@link DynamicInterfaceDataProducer}. This mapping used to be recomputed on every request;
 * it is now resolved once at deploy time, so these tests lock in the
 * {@link QueryMethodParser.Action} to {@link QueryType} mapping.
 */
public class DynamicInterfaceDataProducerTest {

    @Test
    public void findActionMapsToFindByName() {
        assertEquals(QueryType.FIND_BY_NAME,
                DynamicInterfaceDataProducer.queryTypeForByNameAction(QueryMethodParser.Action.FIND));
    }

    @Test
    public void deleteActionMapsToDeleteByName() {
        assertEquals(QueryType.DELETE_BY_NAME,
                DynamicInterfaceDataProducer.queryTypeForByNameAction(QueryMethodParser.Action.DELETE));
    }

    @Test
    public void countActionMapsToCountByName() {
        assertEquals(QueryType.COUNT_BY_NAME,
                DynamicInterfaceDataProducer.queryTypeForByNameAction(QueryMethodParser.Action.COUNT));
    }

    @Test
    public void existsActionMapsToExistsByName() {
        assertEquals(QueryType.EXISTS_BY_NAME,
                DynamicInterfaceDataProducer.queryTypeForByNameAction(QueryMethodParser.Action.EXISTS));
    }

    // --- Deploy-time path: method name -> parser -> query type ---

    @Test
    public void findByMethodNameClassifiesAsFindByName() throws QueryMethodSyntaxException {
        assertEquals(QueryType.FIND_BY_NAME, classify("findByName"));
    }

    @Test
    public void deleteByMethodNameClassifiesAsDeleteByName() throws QueryMethodSyntaxException {
        assertEquals(QueryType.DELETE_BY_NAME, classify("deleteByName"));
    }

    @Test
    public void countByMethodNameClassifiesAsCountByName() throws QueryMethodSyntaxException {
        assertEquals(QueryType.COUNT_BY_NAME, classify("countByName"));
    }

    @Test
    public void existsByMethodNameClassifiesAsExistsByName() throws QueryMethodSyntaxException {
        assertEquals(QueryType.EXISTS_BY_NAME, classify("existsByName"));
    }

    /**
     * Mirrors the deploy-time classification done in {@code addQueries}: parse the method name and
     * map the resulting action to a query type.
     */
    private static QueryType classify(String methodName) throws QueryMethodSyntaxException {
        QueryMethodParser.ParseResult parseResult = new QueryMethodParser(methodName).parse();
        return DynamicInterfaceDataProducer.queryTypeForByNameAction(parseResult.action());
    }

    // --- Entity type declared by a method vs. the repository's primary entity ---

    @Test
    public void countByWithEntityParameterUsesPrimaryEntity() throws NoSuchMethodException {
        assertNull(resolve("countByAuthor", QueryType.COUNT_BY_NAME, Book.class, Author.class));
    }

    @Test
    public void deleteByWithEntityParameterUsesPrimaryEntity() throws NoSuchMethodException {
        assertNull(resolve("deleteByAuthor", QueryType.DELETE_BY_NAME, Book.class, Author.class));
    }

    @Test
    public void existsByWithEntityParameterUsesPrimaryEntity() throws NoSuchMethodException {
        assertNull(resolve("existsByAuthor", QueryType.EXISTS_BY_NAME, Book.class, Author.class));
    }

    @Test
    public void queryWithEntityParameterIsNotResolvedFromParameter() throws NoSuchMethodException {
        assertNull(resolve("countBooksOf", QueryType.QUERY, Book.class, Author.class));
    }

    @Test
    public void findByWithEntityParameterUsesReturnType() throws NoSuchMethodException {
        assertEquals(Book.class, resolve("findByAuthor", QueryType.FIND_BY_NAME, Book.class, Author.class));
    }

    @Test
    public void lifecycleDeleteUsesEntityParameter() throws NoSuchMethodException {
        assertEquals(Author.class, resolve("remove", QueryType.DELETE, Book.class, Author.class));
    }

    @Test
    public void entityParameterIsUsedWithoutPrimaryEntity() throws NoSuchMethodException {
        assertEquals(Author.class, resolve("countByAuthor", QueryType.COUNT_BY_NAME, null, Author.class));
    }

    private static Class<?> resolve(String methodName, QueryType queryType, Class<?> primaryEntityClass,
                                    Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = BookRepository.class.getMethod(methodName, parameterTypes);
        return DynamicInterfaceDataProducer.resolveEntityTypeInMethod(method, queryType, primaryEntityClass);
    }

    @Entity
    public static class Author {
        @Id
        public Long id;
    }

    @Entity
    public static class Book {
        @Id
        public Long id;
        @ManyToOne
        public Author author;
    }

    public interface BookRepository {
        long countByAuthor(Author author);

        void deleteByAuthor(Author author);

        boolean existsByAuthor(Author author);

        List<Book> findByAuthor(Author author);

        @Query("SELECT COUNT(b) FROM Book b WHERE b.author = ?1")
        long countBooksOf(Author author);

        @Delete
        void remove(Author author);
    }
}
