//=============================================================================
//===	Copyright (C) 2001-2007 Food and Agriculture Organization of the
//===	United Nations (FAO-UN), United Nations World Food Programme (WFP)
//===	and United Nations Environment Programme (UNEP)
//===
//===	This program is free software; you can redistribute it and/or modify
//===	it under the terms of the GNU General Public License as published by
//===	the Free Software Foundation; either version 2 of the License, or (at
//===	your option) any later version.
//===
//===	This program is distributed in the hope that it will be useful, but
//===	WITHOUT ANY WARRANTY; without even the implied warranty of
//===	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
//===	General Public License for more details.
//===
//===	You should have received a copy of the GNU General Public License
//===	along with this program; if not, write to the Free Software
//===	Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
//===
//===	Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
//===	Rome - Italy. email: geonetwork@osgeo.org
//==============================================================================

package org.fao.geonet.kernel.harvest.harvester.oaipmh;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.emf.common.command.AbortExecutionException;
import org.fao.geonet.Logger;
import org.fao.geonet.Util;
import org.fao.geonet.constants.Geonet;
import org.fao.geonet.domain.ISODate;
import org.fao.geonet.exceptions.OperationAbortedEx;
import org.fao.geonet.kernel.harvest.BaseAligner;
import org.fao.geonet.kernel.harvest.harvester.HarvestError;
import org.fao.geonet.kernel.harvest.harvester.HarvestResult;
import org.fao.geonet.kernel.harvest.harvester.IHarvester;
import org.fao.geonet.lib.Lib;
import org.fao.geonet.utils.GeonetHttpRequestFactory;
import org.fao.geonet.utils.XmlRequest;
import org.fao.oaipmh.exceptions.NoRecordsMatchException;
import org.fao.oaipmh.requests.ListIdentifiersRequest;
import org.fao.oaipmh.requests.ListRecordsRequest;
import org.fao.oaipmh.requests.ListRequest;
import org.fao.oaipmh.responses.Header;
import org.fao.oaipmh.responses.ListIdentifiersResponse;
import org.fao.oaipmh.responses.ListRecordsResponse;
import org.fao.oaipmh.responses.Record;

import jeeves.server.context.ServiceContext;

//=============================================================================

class Harvester extends BaseAligner<OaiPmhParams> implements IHarvester<HarvestResult> {

    private HarvestResult result;
    private Logger log;
    private ServiceContext context;

    /**
     * Contains a list of accumulated errors during the executing of this harvest.
     */
    private final List<HarvestError> errors;

    public Harvester(AtomicBoolean cancelMonitor, Logger log, ServiceContext context, OaiPmhParams params, List<HarvestError> errors) {
        super(cancelMonitor);
        this.log = log;
        this.context = context;
        this.params = params;
        this.errors = errors;

        result = new HarvestResult();
    }

    //--------------------------------------------------------------------------
    //---
    //--- Private methods : updateMetadata
    //---
    //--------------------------------------------------------------------------

    public HarvestResult harvest(Logger log) throws Exception {

        this.log = log;

        ListRequest req;

        if (params.useListRecords) {
            req = new ListRecordsRequest(context.getBean(GeonetHttpRequestFactory.class));
        } else {
            req = new ListIdentifiersRequest(context.getBean(GeonetHttpRequestFactory.class));
        }

        req.setSchemaPath(context.getAppPath().resolve(Geonet.SchemaPath.OAI_PMH));

        XmlRequest t = req.getTransport();
        try {
            t.setUrl(new URL(params.url));
        } catch (MalformedURLException e1) {
            HarvestError harvestError = new HarvestError(context, e1);
            harvestError.setDescription(harvestError.getDescription() + " " + params.url);
            errors.add(harvestError);
            throw new AbortExecutionException(e1);
        }

        if (params.isUseAccount()) {
            t.setCredentials(params.getUsername(), params.getPassword());
        }

        //--- set the proxy info if necessary
        Lib.net.setupProxy(context, t);

        //--- perform all searches

        Set<RecordInfo> records = new HashSet<>();
        Set<String> uuids = new HashSet<>();

        boolean error = false;
        for (Search s : params.getSearches()) {

            if (cancelMonitor.get()) {
                return this.result;
            }

            try {
                if (params.useListRecords) {
                    AlignerListRecords aligner = new AlignerListRecords(cancelMonitor, context, params, log);
                    searchAndAlign((ListRecordsRequest) req, t, aligner, s, uuids);
                    result = aligner.cleanupRemovedRecords(uuids);
                } else {
                    records = search((ListIdentifiersRequest) req, s);
                }

            } catch (Exception e) {
                error = true;
                log.error("Unknown error trying to harvest");
                log.error(e.getMessage());
                log.error(e);
                errors.add(new HarvestError(context, e));
            } catch (Throwable e) {
                error = true;
                log.fatal("Something unknown and terrible happened while harvesting");
                log.fatal(e.getMessage());
                log.error(e);
                errors.add(new HarvestError(context, e));
            }
        }

        if (params.isSearchEmpty()) {
            try {
                log.debug("Doing an empty search");

                if (params.useListRecords) {
                    AlignerListRecords aligner = new AlignerListRecords(cancelMonitor, context, params, log);
                    searchAndAlign((ListRecordsRequest) req, t, aligner, Search.createEmptySearch(), uuids);
                    result = aligner.cleanupRemovedRecords(uuids);
                } else {
                    search((ListIdentifiersRequest) req, Search.createEmptySearch());
                }

            } catch (Exception e) {
                error = true;
                log.error("Unknown error trying to harvest");
                log.error(e.getMessage());
                log.error(e);
                errors.add(new HarvestError(context, e));
            } catch(Throwable e) {
                error = true;
                log.fatal("Something unknown and terrible happened while harvesting");
                log.fatal(e.getMessage());
                log.error(e);
                errors.add(new HarvestError(context, e));
            }
        }

        log.info("Total records processed in all searches :" + uuids.size());

        if (!params.useListRecords) {
            //--- align local node
            if (!error) {
                AlignerListIdentifiers aligner = new AlignerListIdentifiers(cancelMonitor, context, params, log);

                result = aligner.align(t, records, errors);
            } else {
                log.warning("Due to previous errors the align process has not been called");
            }
        }
        return result;
    }

    private Set<RecordInfo> search(ListIdentifiersRequest req, Search s) throws OperationAbortedEx {
        //--- setup search parameters

        if (!s.from.isEmpty()) req.setFrom(new ISODate(s.from));
        else req.setFrom(null);

        if (!s.until.isEmpty()) req.setUntil(new ISODate(s.until));
        else req.setUntil(null);

        if (!s.set.isEmpty()) req.setSet(s.set);
        else req.setSet(null);

        req.setMetadataPrefix(s.prefix);

        //--- execute request and loop on response

        Set<RecordInfo> records = new HashSet<>();

        log.info("Searching on : " + params.getName());

        try {
            ListIdentifiersResponse response = req.execute();

            while (response.hasNext()) {
                if (cancelMonitor.get()) {
                    return Collections.emptySet();
                }

                Header h = response.next();

                if (!h.isDeleted())
                    records.add(new RecordInfo(h, s.prefix));
            }

            log.info("Records added to result list : " + records.size());

            return records;
        } catch (NoRecordsMatchException e) {
            log.warning("No records were matched: " + e.getMessage());
            this.errors.add(new HarvestError(context, e));
            return records;
        } catch (Exception e) {
            log.warning("Raised exception when searching : " + e);
            log.warning(Util.getStackTrace(e));
            this.errors.add(new HarvestError(context, e));
            throw new OperationAbortedEx("Raised exception when searching", e);
        }
    }

    private void searchAndAlign(ListRecordsRequest req, XmlRequest t, AlignerListRecords aligner, Search s, Set<String> uuids) {
        log.info("Start of alignment for : " + params.getName());

        //--- setup search parameters
        if (!s.from.isEmpty()) req.setFrom(new ISODate(s.from));
        else req.setFrom(null);

        if (!s.until.isEmpty()) req.setUntil(new ISODate(s.until));
        else req.setUntil(null);

        if (!s.set.isEmpty()) req.setSet(s.set);
        else req.setSet(null);

        req.setMetadataPrefix(s.prefix);

        //--- execute request and loop on response

        Set<RecordInfo> records = new HashSet<>();

        log.info("Searching on : " + params.getName());

        try {
            ListRecordsResponse response = req.execute();

            int i = 0;

            while (response.hasNext()) {
                if (cancelMonitor.get()) {
                    return;
                }

                Record record = response.next();

                Header h = record.getHeader();

                if (!h.isDeleted()) {
                    i++;
                    records.add(new RecordInfo(record, s.prefix));
                    uuids.add(h.getIdentifier());
                }

                if (i == 100) {
                    aligner.align(t, records, errors);
                    records = new HashSet<>();
                    i = 0;
                }
            }

            if (i > 0) {
                aligner.align(t, records, errors);
            }

            log.info("End of alignment for : " + params.getName());
        } catch (NoRecordsMatchException e) {
            log.warning("No records were matched: " + e.getMessage());
            this.errors.add(new HarvestError(context, e));
        } catch (Exception e) {
            log.warning("Raised exception when searching : " + e);
            log.warning(Util.getStackTrace(e));
            this.errors.add(new HarvestError(context, e));
            throw new OperationAbortedEx("Raised exception when searching", e);
        }
    }
}
