/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb_testprocs.regressionsuites.executionsitekillers;

import org.voltdb.*;

@ProcInfo (
    partitionInfo = "NEW_ORDER.NO_W_ID: 0",
    singlePartition = true
)
public class SinglePartitionKiller extends VoltProcedure {

    public final SQLStmt insert = new SQLStmt("INSERT INTO NEW_ORDER VALUES (?, ?, ?);");

    public VoltTable[] run(byte w_id) throws VoltAbortException {
        voltQueueSQL(insert, w_id, w_id, w_id);
        VoltTable[] results = voltExecuteSQL();

        // SCARY!  DON'T TRY THIS AT HOME!  Need a way to kill only one
        // replica and had to resort to this.  ABSOLUTELY POSITIVELY NOT
        // ADVISED AND DEFINITELY FROWNED UPON FOR A REAL APPLICATION.
        int host_id = VoltDB.instance().getHostMessenger().getHostId();
        if ((host_id % 2) == 0)
        {
            throw new AssertionError("Site-co killer, q'est que c'est");
        }
        else
        {
            return results;
        }
    }
}
