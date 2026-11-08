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

import fish.payara.data.core.querymethod.QueryMethodParser;
import fish.payara.data.core.querymethod.QueryMethodSyntaxException;
import jakarta.data.Order;
import jakarta.data.Sort;
import jakarta.data.page.PageRequest;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;

/**
 * Tests the argument processing for derived query methods that return a page.
 */
public class QueryByNameOperationUtilityTest {

    private static final PageRequest PAGE_REQUEST = PageRequest.ofSize(2);
    private static final Order<Object> ORDER = Order.by(Sort.asc("title"));

    private static Object[] process(String methodName, Object... args) throws QueryMethodSyntaxException {
        QueryMethodParser.ParseResult parseResult = new QueryMethodParser(methodName).parse();
        return QueryByNameOperationUtility.processArgumentsForPagination(parseResult.conditions(), args);
    }

    @Test
    public void startsWithAppendsWildcard() throws QueryMethodSyntaxException {
        assertArrayEquals(new Object[]{"B%", PAGE_REQUEST, ORDER},
                process("findByTitleStartsWith", "B", PAGE_REQUEST, ORDER));
    }

    @Test
    public void endsWithPrependsWildcard() throws QueryMethodSyntaxException {
        assertArrayEquals(new Object[]{"%a", PAGE_REQUEST, ORDER},
                process("findByTitleEndsWith", "a", PAGE_REQUEST, ORDER));
    }

    @Test
    public void containsWrapsWithWildcards() throws QueryMethodSyntaxException {
        assertArrayEquals(new Object[]{"%et%", PAGE_REQUEST, ORDER},
                process("findByTitleContains", "et", PAGE_REQUEST, ORDER));
    }

    @Test
    public void ignoreCaseLowersValue() throws QueryMethodSyntaxException {
        assertArrayEquals(new Object[]{"b%", PAGE_REQUEST},
                process("findByTitleIgnoreCaseStartsWith", "B", PAGE_REQUEST));
    }

    @Test
    public void likeKeepsCallerPattern() throws QueryMethodSyntaxException {
        assertArrayEquals(new Object[]{"B%", PAGE_REQUEST},
                process("findByTitleLike", "B%", PAGE_REQUEST));
        assertArrayEquals(new Object[]{"b%", PAGE_REQUEST},
                process("findByTitleIgnoreCaseLike", "B%", PAGE_REQUEST));
    }

    @Test
    public void keepsPositionsAndOtherOperators() throws QueryMethodSyntaxException {
        assertArrayEquals(new Object[]{PAGE_REQUEST, 1, 5, "%x", 3L},
                process("findByPagesBetweenAndTitleEndsWithAndAuthorNullAndYearGreaterThan",
                        PAGE_REQUEST, 1, 5, "x", 3L));
    }

    @Test
    public void doesNotModifyGivenArguments() throws QueryMethodSyntaxException {
        Object[] args = {"B", PAGE_REQUEST};
        process("findByTitleStartsWith", args);
        assertArrayEquals(new Object[]{"B", PAGE_REQUEST}, args);
    }

    @Test
    public void nullArgumentsStayNull() {
        assertSame(null, QueryByNameOperationUtility.processArgumentsForPagination(java.util.List.of(), null));
    }
}
