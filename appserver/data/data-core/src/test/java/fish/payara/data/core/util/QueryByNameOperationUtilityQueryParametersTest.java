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
import jakarta.persistence.Query;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Tests the parameter values bound for derived query methods that do not return a page.
 */
public class QueryByNameOperationUtilityQueryParametersTest {

    private static List<Object> bind(String methodName, Object... args) throws QueryMethodSyntaxException {
        QueryMethodParser.ParseResult parseResult = new QueryMethodParser(methodName).parse();
        List<Object> bound = new ArrayList<>();
        Query query = (Query) Proxy.newProxyInstance(Query.class.getClassLoader(), new Class<?>[]{Query.class},
                (proxy, method, methodArgs) -> {
                    if ("setParameter".equals(method.getName()) && methodArgs.length == 2
                            && methodArgs[0] instanceof Integer position) {
                        assertEquals(bound.size() + 1, position.intValue());
                        bound.add(methodArgs[1]);
                        return proxy;
                    }
                    throw new UnsupportedOperationException(method.toString());
                });
        QueryByNameOperationUtility.setQueryParameters(query, parseResult.conditions(), args, null);
        return bound;
    }

    @Test
    public void likeBindsCallerPattern() throws QueryMethodSyntaxException {
        assertEquals(List.of("%e"), bind("findByTitleLike", "%e"));
        assertEquals(List.of("Bet"), bind("findByTitleLike", "Bet"));
    }

    @Test
    public void ignoreCaseLikeLowersCallerPattern() throws QueryMethodSyntaxException {
        assertEquals(List.of("a%"), bind("findByTitleIgnoreCaseLike", "A%"));
    }

    @Test
    public void notLikeBindsCallerPattern() throws QueryMethodSyntaxException {
        assertEquals(List.of("B_ta"), bind("findByTitleNotLike", "B_ta"));
    }

    @Test
    public void startsWithEndsWithContainsAddWildcards() throws QueryMethodSyntaxException {
        assertEquals(List.of("B%"), bind("findByTitleStartsWith", "B"));
        assertEquals(List.of("%a"), bind("findByTitleEndsWith", "a"));
        assertEquals(List.of("%et%"), bind("findByTitleContains", "et"));
        assertEquals(List.of("%et%"), bind("findByTitleIgnoreCaseContains", "ET"));
    }

    @Test
    public void otherOperatorsBindArgumentsAsGiven() throws QueryMethodSyntaxException {
        assertEquals(List.of(1, 5, "%x", 3L),
                bind("findByPagesBetweenAndTitleEndsWithAndAuthorNullAndYearGreaterThan", 1, 5, "x", 3L));
    }
}
