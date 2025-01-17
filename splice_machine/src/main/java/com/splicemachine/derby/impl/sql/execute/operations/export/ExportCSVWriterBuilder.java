/*
 * Copyright (c) 2012 - 2020 Splice Machine, Inc.
 *
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

package com.splicemachine.derby.impl.sql.execute.operations.export;

import com.splicemachine.db.iapi.sql.ResultColumnDescriptor;
import com.splicemachine.db.iapi.types.DataTypeDescriptor;
import com.splicemachine.db.iapi.types.TypeId;
import org.supercsv.io.CsvListWriter;
import org.supercsv.prefs.CsvPreference;
import org.supercsv.quote.ColumnQuoteMode;

import java.io.*;
import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Constructs/configures CsvListWriter objects used during export.
 */
public class ExportCSVWriterBuilder {

    /* On a local 4-node cluster varying this buffer size by 3 orders of magnitude had almost no effect. */
    private static final int WRITE_BUFFER_SIZE_BYTES = 16 * 1024;

    public CsvListWriter build(OutputStream outputStream, ExportParams exportParams, ResultColumnDescriptor[] exportColumns) throws IOException {
        OutputStreamWriter stream = new OutputStreamWriter(outputStream, exportParams.getCharacterEncoding());
        Writer writer = new BufferedWriter(stream, WRITE_BUFFER_SIZE_BYTES);
        CsvPreference.Builder preference = new CsvPreference.Builder(
                exportParams.getQuoteChar(),
                exportParams.getFieldDelimiter(),
                exportParams.getRecordDelimiter());
        switch (exportParams.getQuoteMode()) {
            case ALWAYS:
                boolean[] toQuote = new boolean[exportColumns.length];
                for (int i = 0; i < exportColumns.length; ++i) {
                    TypeId typeId = exportColumns[i].getType().getTypeId();
                    toQuote[i] = typeId.isCharOrVarChar() || typeId.isBitTypeId();
                }
                preference.useQuoteMode(new ColumnQuoteMode(toQuote));
                break;
            case DEFAULT:
                preference.useQuoteMode(new ColumnQuoteMode());
                break;
            default:
                assert false;
        }
        return new CsvListWriter(writer, preference.build());
    }

}
