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
package org.lilyproject.rest;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.util.List;

import org.codehaus.jackson.node.ObjectNode;
import org.lilyproject.repository.api.RepositoryTable;
import org.lilyproject.repository.api.RepositoryTableManager;
import org.lilyproject.repository.api.RepositoryTableManager.TableCreateDescriptor;
import org.lilyproject.repository.impl.TableCreateDescriptorImpl;
import org.springframework.beans.factory.annotation.Autowired;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;

@Path("table")
public class TableResource {

    private RepositoryTableManager tableManager;

    @Autowired
    public void setTableManager(RepositoryTableManager tableManager) {
        this.tableManager = tableManager;
    }

    /**
     * Get all record table names.
     */
    @GET
    @Produces("application/json")
    public List<RepositoryTable> get(@Context UriInfo uriInfo) {
        try {
            return tableManager.getTables();
        } catch (Exception e) {
            throw new ResourceException("Error getting repository tables", e, INTERNAL_SERVER_ERROR.getStatusCode());
        }
    }

    @DELETE
    @Path("{name}")
    public Response dropTable(@PathParam("name") String tableName) {
        try {
            if (!tableManager.tableExists(tableName)) {
                throw new ResourceException("Table '" + tableName + "' not found", NOT_FOUND.getStatusCode());
            }
            tableManager.dropTable(tableName);
            return Response.ok().build();
        } catch (IOException ioe) {
            throw new ResourceException("Error dropping table '" + tableName + "'", ioe,
                    INTERNAL_SERVER_ERROR.getStatusCode());
        } catch (InterruptedException ie) {
            throw new ResourceException("Interrupted while dropping table '" + tableName + "'", ie,
                    INTERNAL_SERVER_ERROR.getStatusCode());
        }
    }

    // TODO Allow providing split information
    @POST
    @Consumes("application/json")
    public Response createTable(ObjectNode descriptorJson) {
        try {
            if (!descriptorJson.has("name")) {
                throw new ResourceException("Name must be included in POST body", BAD_REQUEST.getStatusCode());
            }
            String tableName = descriptorJson.get("name").asText();
            String keyPrefix = null;
            TableCreateDescriptor descriptor = null;
            if (descriptorJson.has("keyPrefix")) {
                keyPrefix = descriptorJson.get("keyPrefix").asText();
            }
            if (descriptorJson.has("splitKeys")) {
                descriptor = TableCreateDescriptorImpl.createInstanceWithSplitKeys(tableName, keyPrefix, descriptorJson.get("splitKeys").asText());
            } else

            if (descriptorJson.has("numRegions")) {
               descriptor = TableCreateDescriptorImpl.createInstance(tableName, keyPrefix, descriptorJson.get("numRegions").asInt());
            } else {
                descriptor = TableCreateDescriptorImpl.createInstance(tableName);
            }
            tableManager.createTable(descriptor);
            return Response.ok().build();
        } catch (Exception e) {
            throw new ResourceException("Error creating table", e, INTERNAL_SERVER_ERROR.getStatusCode());
        }
    }
}
