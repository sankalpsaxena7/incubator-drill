/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package org.apache.drill.exec.expr.fn.impl;

import org.apache.drill.common.expression.*;
import org.apache.drill.exec.expr.DrillFunc;
import org.apache.drill.exec.expr.annotations.FunctionTemplate;
import org.apache.drill.exec.expr.annotations.Output;
import org.apache.drill.exec.expr.annotations.Param;
import org.apache.drill.exec.expr.holders.*;
import org.apache.drill.exec.record.RecordBatch;
import org.apache.drill.exec.vector.BitHolder;

@FunctionTemplate(name = "isNull", scope = FunctionTemplate.FunctionScope.SIMPLE)
public class IsNull implements DrillFunc {

  @Param NullableFloat8Holder input;
  @Output BitHolder out;

  public void setup(RecordBatch incoming) { }

  public void eval() {
    out.value = (input.isSet == 0 ? 1 : 0);
  }

  public static class Provider implements CallProvider {

    @Override
    public FunctionDefinition[] getFunctionDefintions() {
      return new FunctionDefinition[] {
          FunctionDefinition.simple("isNull",
                                    new ArgumentValidators.AnyTypeAllowed(1),
                                    OutputTypeDeterminer.FIXED_BIT,
                                    "isnull")
      };
    }

  }

}

