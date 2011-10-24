/*
 * Copyright 2010 Outerthought bvba
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
package org.lilyproject.linkindex;

import org.apache.hadoop.hbase.util.Bytes;
import org.lilyproject.hbaseindex.*;
import org.lilyproject.linkindex.LinkIndexMetrics.Action;
import org.lilyproject.repository.api.*;
import org.lilyproject.util.Pair;
import org.lilyproject.util.io.Closer;

import java.io.IOException;
import java.util.*;

/**
 * The index of links that exist between documents.
 */
// IMPORTANT implementation note: the order in which changes are applied, first to the forward or first to
// the backward table, is not arbitrary. It is such that if the process would fail in between, there would
// never be left any state in the backward table which would not be found via the forward index.
public class LinkIndex {
    private IdGenerator idGenerator;
    private LinkIndexMetrics metrics;
    private static ThreadLocal<Index> FORWARD_INDEX;
    private static ThreadLocal<Index> BACKWARD_INDEX;

    private static final byte[] SOURCE_FIELD_KEY = Bytes.toBytes("sf");
    private static final byte[] VTAG_KEY = Bytes.toBytes("vt");

    public LinkIndex(final IndexManager indexManager, Repository repository) throws IndexNotFoundException, IOException {
        metrics = new LinkIndexMetrics("linkIndex");
        this.idGenerator = repository.getIdGenerator();

        // About the structure of these indexes:
        //  - the vtag comes after the recordid because this way we can delete all
        //    entries for a record without having to know the vtags under which they occur
        //  - the sourcefield will often by optional in queries, that's why it comes last

        FORWARD_INDEX = new ThreadLocal<Index>() {
            @Override
            protected Index initialValue() {
                try {
                    IndexDefinition indexDef = new IndexDefinition("links-forward");
                    indexDef.addByteField("source");
                    indexDef.addByteField("vtag");
                    indexDef.addByteField("sourcefield");
                    return indexManager.getIndex(indexDef);
                } catch (Exception e) {
                    throw new RuntimeException("Error accessing forward links index.", e);
                }
            }
        };

        BACKWARD_INDEX = new ThreadLocal<Index>() {
            @Override
            protected Index initialValue() {
                try {
                    IndexDefinition indexDef = new IndexDefinition("links-backward");
                    indexDef.addByteField("target");
                    indexDef.addByteField("vtag");
                    indexDef.addByteField("sourcefield");
                    return indexManager.getIndex(indexDef);
                } catch (Exception e) {
                    throw new RuntimeException("Error accessing backward links index.", e);
                }
            }
        };

        // Do a get now to be sure the indexes exist or can be successfully created
        FORWARD_INDEX.get();
        BACKWARD_INDEX.get();
    }

    /**
     * Deletes all links of a record, irrespective of the vtag.
     */
    public void deleteLinks(RecordId sourceRecord) throws LinkIndexException {
        long before = System.currentTimeMillis();
        try {
            byte[] sourceAsBytes = sourceRecord.toBytes();
    
            // Read links from the forwards table
            Set<Pair<FieldedLink, SchemaId>> oldLinks = getAllForwardLinks(sourceRecord);
    
            // Delete existing entries from the backwards table
            List<IndexEntry> entries = new ArrayList<IndexEntry>(oldLinks.size());
            for (Pair<FieldedLink, SchemaId> link : oldLinks) {
                IndexEntry entry = createBackwardIndexEntry(link.getV2(), link.getV1().getRecordId(), link.getV1().getFieldTypeId());
                entry.setIdentifier(sourceAsBytes);
                entries.add(entry);
            }
            BACKWARD_INDEX.get().removeEntries(entries);
    
            // Delete existing entries from the forwards table
            entries.clear();
            for (Pair<FieldedLink, SchemaId> link : oldLinks) {
                IndexEntry entry = createForwardIndexEntry(link.getV2(), sourceRecord, link.getV1().getFieldTypeId());
                entry.setIdentifier(link.getV1().getRecordId().toBytes());
                entries.add(entry);
            }
            FORWARD_INDEX.get().removeEntries(entries);
        } catch (LinkIndexException e) {
            throw new LinkIndexException("Error deleting links for record '" + sourceRecord + "'", e);
        } catch (IOException e) {
            throw new LinkIndexException("Error deleting links for record '" + sourceRecord + "'", e);
        } finally {
            metrics.report(Action.DELETE_LINKS, System.currentTimeMillis() - before);
        }
    }

    public void deleteLinks(RecordId sourceRecord, SchemaId vtag) throws LinkIndexException {
        long before = System.currentTimeMillis();
        try {
            byte[] sourceAsBytes = sourceRecord.toBytes();
    
            // Read links from the forwards table
            Set<FieldedLink> oldLinks = getFieldedForwardLinks(sourceRecord, vtag);
    
            // Delete existing entries from the backwards table
            List<IndexEntry> entries = new ArrayList<IndexEntry>(oldLinks.size());
            for (FieldedLink link : oldLinks) {
                IndexEntry entry = createBackwardIndexEntry(vtag, link.getRecordId(), link.getFieldTypeId());
                entry.setIdentifier(sourceAsBytes);
                entries.add(entry);
            }
            BACKWARD_INDEX.get().removeEntries(entries);
    
            // Delete existing entries from the forwards table
            entries.clear();
            for (FieldedLink link : oldLinks) {
                IndexEntry entry = createForwardIndexEntry(vtag, sourceRecord, link.getFieldTypeId());
                entry.setIdentifier(link.getRecordId().toBytes());
                entries.add(entry);
            }
            FORWARD_INDEX.get().removeEntries(entries);
        } catch (LinkIndexException e) {
            throw new LinkIndexException("Error deleting links for record '" + sourceRecord + "', vtag '" + vtag + "'", e);
        } catch (IOException e) {
            throw new LinkIndexException("Error deleting links for record '" + sourceRecord + "', vtag '" + vtag + "'", e);
        } finally {
            metrics.report(Action.DELETE_LINKS_VTAG, System.currentTimeMillis() - before);
        }
    }

    public void updateLinks(RecordId sourceRecord, SchemaId vtag, Set<FieldedLink> links) throws LinkIndexException {
        updateLinks(sourceRecord, vtag, links, false);
    }

    /**
     *
     * @param links if this set is empty, then calling this method is equivalent to calling deleteLinks
     * @param isNewRecord if this is a new record, then we can skip querying the existing links, thus gaining some time.
     */
    public void updateLinks(RecordId sourceRecord, SchemaId vtag, Set<FieldedLink> links, boolean isNewRecord)
            throws LinkIndexException {
        long before = System.currentTimeMillis();
        try {
            // We could simply delete all the old entries using deleteLinks() and then add
            // all new entries, but instead we find out what actually needs adding or removing and only
            // perform that. This is to avoid running into problems due to http://search-hadoop.com/m/rNnhN15Xecu
            // (= delete and put within the same millisecond).

            Set<FieldedLink> oldLinks = isNewRecord ?
                    Collections.<FieldedLink>emptySet() : getFieldedForwardLinks(sourceRecord, vtag);

            if (links.isEmpty() && oldLinks.isEmpty()) {
                // No links to add, no links to remove
                return;
            }
    
            // Find out what changed
            Set<FieldedLink> removedLinks = new HashSet<FieldedLink>(oldLinks);
            removedLinks.removeAll(links);
            Set<FieldedLink> addedLinks = new HashSet<FieldedLink>(links);
            addedLinks.removeAll(oldLinks);
    
            // Apply added links
            byte[] sourceAsBytes = sourceRecord.toBytes();
            List<IndexEntry> fwdEntries = null;
            List<IndexEntry> bkwdEntries = null;
            if (addedLinks.size() > 0) {
                fwdEntries = new ArrayList<IndexEntry>(Math.max(addedLinks.size(), removedLinks.size()));
                bkwdEntries = new ArrayList<IndexEntry>(fwdEntries.size());
                for (FieldedLink link : addedLinks) {
                    IndexEntry fwdEntry = createForwardIndexEntry(vtag, sourceRecord, link.getFieldTypeId());
                    fwdEntry.setIdentifier(link.getRecordId().toBytes());
                    fwdEntries.add(fwdEntry);
    
                    IndexEntry bkwdEntry = createBackwardIndexEntry(vtag, link.getRecordId(), link.getFieldTypeId());
                    bkwdEntry.setIdentifier(sourceAsBytes);
                    bkwdEntries.add(bkwdEntry);
                }
                FORWARD_INDEX.get().addEntries(fwdEntries);
                BACKWARD_INDEX.get().addEntries(bkwdEntries);
            }
    
            // Apply removed links
            if (removedLinks.size() > 0) {
                if (fwdEntries != null) {
                    fwdEntries.clear();
                    bkwdEntries.clear();
                } else {
                    fwdEntries = new ArrayList<IndexEntry>(removedLinks.size());
                    bkwdEntries = new ArrayList<IndexEntry>(fwdEntries.size());
                }
    
                for (FieldedLink link : removedLinks) {
                    IndexEntry bkwdEntry = createBackwardIndexEntry(vtag, link.getRecordId(), link.getFieldTypeId());
                    bkwdEntry.setIdentifier(sourceAsBytes);
                    bkwdEntries.add(bkwdEntry);
    
                    IndexEntry fwdEntry = createForwardIndexEntry(vtag, sourceRecord, link.getFieldTypeId());
                    fwdEntry.setIdentifier(link.getRecordId().toBytes());
                    fwdEntries.add(fwdEntry);
                }
                BACKWARD_INDEX.get().removeEntries(bkwdEntries);
                FORWARD_INDEX.get().removeEntries(fwdEntries);
            }
        } catch (IOException e) {
            throw new LinkIndexException("Error updating links for record '" + sourceRecord + "', vtag '" +
                    vtag + "'", e);
        } finally {
            metrics.report(Action.UPDATE_LINKS, System.currentTimeMillis() - before);
        }
    }

    private IndexEntry createBackwardIndexEntry(SchemaId vtag, RecordId target, SchemaId sourceField) {
        IndexEntry entry = new IndexEntry();

        entry.addField("vtag", vtag.getBytes());
        entry.addField("target", target.toBytes());
        entry.addField("sourcefield", sourceField.getBytes());

        entry.addData(SOURCE_FIELD_KEY, sourceField.getBytes());

        return entry;
    }

    private IndexEntry createForwardIndexEntry(SchemaId vtag, RecordId source, SchemaId sourceField) {
        IndexEntry entry = new IndexEntry();

        entry.addField("vtag", vtag.getBytes());
        entry.addField("source", source.toBytes());
        entry.addField("sourcefield", sourceField.getBytes());

        entry.addData(SOURCE_FIELD_KEY, sourceField.getBytes());
        entry.addData(VTAG_KEY, vtag.getBytes());

        return entry;
    }

    public Set<RecordId> getReferrers(RecordId record, SchemaId vtag) throws LinkIndexException {
        return getReferrers(record, vtag, null);
    }

    public Set<RecordId> getReferrers(RecordId record, SchemaId vtag, SchemaId sourceField) throws LinkIndexException {
        long before = System.currentTimeMillis();
        try {
            Query query = new Query();
            query.addEqualsCondition("target", record.toBytes());
            if (vtag != null) {
                query.addEqualsCondition("vtag", vtag.getBytes());
            }
            if (sourceField != null) {
                query.addEqualsCondition("sourcefield", sourceField.getBytes());
            }
    
            Set<RecordId> result = new HashSet<RecordId>();
    
            QueryResult qr = BACKWARD_INDEX.get().performQuery(query);
            byte[] id;
            while ((id = qr.next()) != null) {
                result.add(idGenerator.fromBytes(id));
            }
            Closer.close(qr); // Not closed in finally block: avoid HBase contact when there could be connection problems.
    
            return result;
        } catch (IOException e) {
            throw new LinkIndexException("Error getting referrers for record '" + record + "', vtag '" + vtag +
                "', field '" + sourceField + "'", e);
        } finally {
            metrics.report(Action.GET_REFERRERS, System.currentTimeMillis() - before);
        }
    }

    public Set<FieldedLink> getFieldedReferrers(RecordId record, SchemaId vtag) throws LinkIndexException {
        long before = System.currentTimeMillis();
        try {
            Query query = new Query();
            query.addEqualsCondition("target", record.toBytes());
            if (vtag != null) {
                query.addEqualsCondition("vtag", vtag.getBytes());
            }
    
            Set<FieldedLink> result = new HashSet<FieldedLink>();
    
            QueryResult qr = BACKWARD_INDEX.get().performQuery(query);
            byte[] id;
            while ((id = qr.next()) != null) {
                SchemaId sourceField = idGenerator.getSchemaId(qr.getData(SOURCE_FIELD_KEY));
                result.add(new FieldedLink(idGenerator.fromBytes(id), sourceField));
            }
            Closer.close(qr); // Not closed in finally block: avoid HBase contact when there could be connection problems.
    
            return result;
        } catch (IOException e) {
            throw new LinkIndexException("Error getting referrers for record '" + record + "', vtag '" + vtag + "'", e);
        } finally {
            metrics.report(Action.GET_FIELDED_REFERRERS, System.currentTimeMillis() - before);
        }
    }

    public Set<Pair<FieldedLink, SchemaId>> getAllForwardLinks(RecordId record) throws LinkIndexException {
        long before = System.currentTimeMillis();
        try {
            Query query = new Query();
            query.addEqualsCondition("source", record.toBytes());

            Set<Pair<FieldedLink, SchemaId>> result = new HashSet<Pair<FieldedLink, SchemaId>>();
    
            QueryResult qr = FORWARD_INDEX.get().performQuery(query);
            byte[] id;
            while ((id = qr.next()) != null) {
                SchemaId sourceField = idGenerator.getSchemaId(qr.getData(SOURCE_FIELD_KEY));
                SchemaId vtag = idGenerator.getSchemaId(qr.getData(VTAG_KEY));
                result.add(new Pair<FieldedLink, SchemaId>(new FieldedLink(idGenerator.fromBytes(id), sourceField), vtag));
            }
            Closer.close(qr); // Not closed in finally block: avoid HBase contact when there could be connection problems.
    
            return result;
        } catch (IOException e) {
            throw new LinkIndexException("Error getting forward links for record '" + record + "'", e);
        } finally {
            metrics.report(Action.GET_ALL_FW_LINKS, System.currentTimeMillis() - before);
        }
    }

    public Set<FieldedLink> getFieldedForwardLinks(RecordId record, SchemaId vtag) throws LinkIndexException {
        long before = System.currentTimeMillis();
        try {
            Query query = new Query();
            query.addEqualsCondition("source", record.toBytes());
            if (vtag != null) {
                query.addEqualsCondition("vtag", vtag.getBytes());
            }
    
            Set<FieldedLink> result = new HashSet<FieldedLink>();
    
            QueryResult qr = FORWARD_INDEX.get().performQuery(query);
            byte[] id;
            while ((id = qr.next()) != null) {
                SchemaId sourceField = idGenerator.getSchemaId(qr.getData(SOURCE_FIELD_KEY));
                result.add(new FieldedLink(idGenerator.fromBytes(id), sourceField));
            }
            Closer.close(qr); // Not closed in finally block: avoid HBase contact when there could be connection problems.
    
            return result;
        } catch (IOException e) {
            throw new LinkIndexException("Error getting forward links for record '" + record + "', vtag '" +
                    vtag + "'", e);
        } finally {
            metrics.report(Action.GET_FW_LINKS, System.currentTimeMillis() - before);
        }
    }

    public Set<RecordId> getForwardLinks(RecordId record, SchemaId vtag, SchemaId sourceField) throws LinkIndexException {
        long before = System.currentTimeMillis();
        try {
            Query query = new Query();
            query.addEqualsCondition("source", record.toBytes());
            if (vtag != null) {
                query.addEqualsCondition("vtag", vtag.getBytes());
            }
            if (sourceField != null) {
                query.addEqualsCondition("sourcefield", sourceField.getBytes());
            }

            Set<RecordId> result = new HashSet<RecordId>();

            QueryResult qr = FORWARD_INDEX.get().performQuery(query);
            byte[] id;
            while ((id = qr.next()) != null) {
                result.add(idGenerator.fromBytes(id));
            }
            Closer.close(qr); // Not closed in finally block: avoid HBase contact when there could be connection problems.

            return result;
        } catch (IOException e) {
            throw new LinkIndexException("Error getting forward links for record '" + record + "', vtag '" +
                    vtag + "', field '" + sourceField + "'", e);
        } finally {
            metrics.report(Action.GET_FW_LINKS, System.currentTimeMillis() - before);
        }
    }
}
