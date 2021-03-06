/*
 * Copyright © 2020 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.plugin.batch.aggregator.function;

import io.cdap.cdap.api.data.format.StructuredRecord;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.cdap.api.data.schema.Schema.Type;

/**
 * Returns the longest string in the group
 */
public class LongestString implements AggregateFunction<String, LongestString> {

  private final String fieldName;
  private String longestString;

  public LongestString(String fieldName, Schema fieldSchema) {
    this.fieldName = fieldName;
    Type inputType = fieldSchema.isNullable() ? fieldSchema.getNonNullable().getType() : fieldSchema.getType();

    if (!inputType.equals(Type.STRING)) {
      throw new IllegalArgumentException(
        String.format("Field '%s' is of unsupported non-string type '%s'. ", fieldName, inputType));
    }
  }

  @Override
  public void initialize() {
    longestString = "";
  }

  @Override
  public void mergeValue(StructuredRecord record) {
    String value = record.get(fieldName);
    if (value != null) {
      if (value.length() > longestString.length()) {
        longestString = value;
      }
    }
  }

  @Override
  public void mergeAggregates(LongestString otherAgg) {
    if (otherAgg.getAggregate() == null) {
      return;
    }
    if (otherAgg.getAggregate().length() > longestString.length()) {
      longestString = otherAgg.getAggregate();
    }
  }

  @Override
  public String getAggregate() {
    return longestString;
  }

  @Override
  public Schema getOutputSchema() {
    return Schema.of(Type.STRING);
  }

}
