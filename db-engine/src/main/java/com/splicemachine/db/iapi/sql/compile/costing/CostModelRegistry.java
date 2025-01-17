/*
 * Copyright (c) 2012 - 2021 Splice Machine, Inc.
 * This file is part of Splice Machine.
 * Splice Machine is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3, or (at your option) any later version.
 * Splice Machine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License along with Splice Machine.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package com.splicemachine.db.iapi.sql.compile.costing;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CostModelRegistry {

    private static final Map<String, CostModel> registry = new ConcurrentHashMap<>();

    public static void registerCostModel(String name, CostModel model) {
        registry.put(name, model);
    }

    public static void unregisterCostModel(String name) {
        registry.remove(name);
    }

    public static CostModel getCostModel(String name) {
        return registry.get(name);
    }

    public static boolean exists(String modelName) {
        return registry.containsKey(modelName);
    }

    public static Set<String> modelNames() {
        return registry.keySet();
    }
}
