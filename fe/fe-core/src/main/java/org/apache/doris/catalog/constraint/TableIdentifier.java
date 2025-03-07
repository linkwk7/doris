// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.catalog.constraint;

import org.apache.doris.catalog.DatabaseIf;
import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.TableIf;

import com.google.common.base.Preconditions;

import java.util.Objects;

class TableIdentifier {
    private final long databaseId;
    private final long tableId;

    TableIdentifier(TableIf tableIf) {
        Preconditions.checkArgument(tableIf != null,
                "Table can not be null in constraint");
        databaseId = tableIf.getDatabase().getId();
        tableId = tableIf.getId();
    }

    TableIf toTableIf() {
        DatabaseIf databaseIf = Env.getCurrentEnv().getCurrentCatalog().getDbNullable(databaseId);
        if (databaseIf == null) {
            throw new RuntimeException(String.format("Can not find database %s in constraint", databaseId));
        }
        TableIf tableIf = databaseIf.getTableNullable(tableId);
        if (tableIf == null) {
            throw new RuntimeException(String.format("Can not find table %s in constraint", databaseId));
        }
        return tableIf;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TableIdentifier that = (TableIdentifier) o;
        return databaseId == that.databaseId
                && tableId == that.tableId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(databaseId, tableId);
    }

    @Override
    public String toString() {
        TableIf tableIf = this.toTableIf();
        return String.format("%s.%s", tableIf.getDatabase().getFullName(), tableIf.getName());
    }
}
