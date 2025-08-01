---
id: sql-multivalue-string-functions
title: "SQL multi-value string functions"
sidebar_label: "Multi-value string functions"
---

<!--
  ~ Licensed to the Apache Software Foundation (ASF) under one
  ~ or more contributor license agreements.  See the NOTICE file
  ~ distributed with this work for additional information
  ~ regarding copyright ownership.  The ASF licenses this file
  ~ to you under the Apache License, Version 2.0 (the
  ~ "License"); you may not use this file except in compliance
  ~ with the License.  You may obtain a copy of the License at
  ~
  ~   http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing,
  ~ software distributed under the License is distributed on an
  ~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  ~ KIND, either express or implied.  See the License for the
  ~ specific language governing permissions and limitations
  ~ under the License.
  -->

<!--
  The format of the tables that describe the functions and operators
  should not be changed without updating the script create-sql-docs
  in web-console/script/create-sql-docs, because the script detects
  patterns in this markdown file and parse it to TypeScript file for web console
-->


:::info
 Apache Druid supports two query languages: Druid SQL and [native queries](querying.md).
 This document describes the SQL language.
:::

Druid supports string dimensions containing multiple values.
This page describes the operations you can perform on multi-value string dimensions using [Druid SQL](./sql.md).
See [SQL multi-value strings](./sql-data-types.md#multi-value-strings) and native [Multi-value dimensions](multi-value-dimensions.md) for more information.

All array references in the multi-value string function documentation can refer to multi-value string columns or
`ARRAY` types. These functions are largely identical to the [array functions](./sql-array-functions.md), but use
`VARCHAR` types and behavior. Multi-value strings can also be converted to `ARRAY` types using `MV_TO_ARRAY`, and
`ARRAY` into multi-value strings via `ARRAY_TO_MV`. For additional details about `ARRAY` types, see
[`ARRAY` data type documentation](./sql-data-types.md#arrays).

|Function|Description|
|--------|-----|
|`MV_FILTER_ONLY(expr, arr)`|Filters multi-value `expr` to include only values contained in array `arr`.|
|`MV_FILTER_NONE(expr, arr)`|Filters multi-value `expr` to include no values contained in array `arr`.|
|`MV_FILTER_REGEX(expr, pattern)`|Filters multi-value `expr` to include values that match `pattern`.|
|`MV_FILTER_PREFIX(expr, prefix)`|Filters multi-value `expr` to include values that have prefix `prefix`.|
|`MV_LENGTH(arr)`|Returns length of the array expression.|
|`MV_CONTAINS(arr, expr)`|If `expr` is a scalar type, returns true if `arr` contains `expr`. If `expr` is an array, returns true if `arr` contains all elements of `expr`. Otherwise returns false.|
|`MV_OVERLAP(arr1, arr2)`|Returns true if `arr1` and `arr2` have any elements in common, else false.|
|`MV_OFFSET(arr, long)`|Returns the array element at the 0-based index supplied, or null for an out of range index.|
|`MV_OFFSET_OF(arr, expr)`|Returns the 0-based index of the first occurrence of `expr` in the array. If no matching elements exist in the array, returns `null`.|
|`MV_ORDINAL(arr, long)`|Returns the array element at the 1-based index supplied, or null for an out of range index.|
|`MV_ORDINAL_OF(arr, expr)`|Returns the 1-based index of the first occurrence of `expr` in the array. If no matching elements exist in the array, returns `null`.|
|`MV_PREPEND(expr, arr)`|Adds `expr` to the beginning of `arr`, the resulting array type determined by the type `arr`.|
|`MV_APPEND(arr, expr)`|Appends `expr` to `arr`, the resulting array type determined by the type of `arr`.|
|`MV_CONCAT(arr1, arr2)`|Concatenates `arr2` to `arr1`. The resulting array type is determined by the type of `arr1`.|
|`MV_SLICE(arr, start, end)`|Returns the subarray of `arr` from the zero-based index of `start` (inclusive) to `end` (exclusive). Returns null when `start` is less than 0, greater than the array length, or greater than `end`. When `end` is greater than the array length, null values are appended to the subarray.|
|`MV_TO_STRING(arr, str)`|Joins all elements of `arr` by the delimiter specified by `str`.|
|`STRING_TO_MV(str1, str2)`|Splits `str1` into an array on the delimiter specified by `str2`, which is a regular expression.|
|`MV_TO_ARRAY(str)`|Converts a multi-value string from a `VARCHAR` to a `VARCHAR ARRAY`.|
