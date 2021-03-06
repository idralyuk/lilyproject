/*
 * Copyright 2012 NGDATA nv
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lilyproject.repository.impl.filter;

import java.util.Arrays;

import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.PrefixFilter;
import org.lilyproject.repository.api.RepositoryException;
import org.lilyproject.repository.api.RepositoryManager;
import org.lilyproject.repository.api.filter.RecordFilter;
import org.lilyproject.repository.api.filter.RecordVariantFilter;
import org.lilyproject.repository.impl.hbase.LilyRecordVariantFilter;
import org.lilyproject.repository.spi.HBaseRecordFilterFactory;

public class HBaseRecordVariantFilter implements HBaseRecordFilterFactory {
    @Override
    public Filter createHBaseFilter(RecordFilter uncastFilter, RepositoryManager repositoryManager, HBaseRecordFilterFactory factory)
            throws RepositoryException, InterruptedException {

        if (!(uncastFilter instanceof RecordVariantFilter)) {
            return null;
        }

        final RecordVariantFilter filter = (RecordVariantFilter) uncastFilter;

        if (filter.getMasterRecordId() == null) {
            throw new IllegalArgumentException("Record ID should be specified in RecordVariantFilter");
        }

        if (filter.getVariantProperties() == null) {
            throw new IllegalArgumentException("VariantProperties should be specified in RecordVariantFilter");
        }

        return new FilterList(Arrays.<Filter>asList(
                new PrefixFilter(filter.getMasterRecordId().getMaster().toBytes()),
                new LilyRecordVariantFilter(filter.getVariantProperties())));

    }
}
