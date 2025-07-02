package org.fao.geonet.kernel.harvest.harvester.oaipmh;

import jeeves.server.context.ServiceContext;
import org.apache.commons.lang.StringUtils;
import org.fao.geonet.Logger;
import org.fao.geonet.domain.AbstractMetadata;
import org.fao.geonet.domain.Metadata;
import org.fao.geonet.domain.MetadataType;
import org.fao.geonet.domain.Pair;
import org.fao.geonet.kernel.UpdateDatestamp;
import org.fao.geonet.kernel.harvest.harvester.HarvestError;
import org.fao.geonet.kernel.harvest.harvester.HarvestResult;
import org.fao.geonet.kernel.harvest.harvester.HarvesterUtil;
import org.fao.geonet.kernel.search.IndexingMode;
import org.fao.geonet.repository.MetadataValidationRepository;
import org.fao.geonet.repository.OperationAllowedRepository;
import org.fao.geonet.repository.specification.MetadataValidationSpecs;
import org.fao.geonet.utils.Xml;
import org.fao.geonet.utils.XmlRequest;
import org.jdom.Element;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;


public class AlignerListRecords extends Aligner {

    public AlignerListRecords(AtomicBoolean cancelMonitor, ServiceContext sc, OaiPmhParams params, Logger log) {
        super(cancelMonitor, sc, params, log);
    }

    public HarvestResult align(XmlRequest t, Set<RecordInfo> records, Collection<HarvestError> errors) throws Exception {

        Pair<String, Map<String, Object>> filter = HarvesterUtil.parseXSLFilter(params.xslfilter);
        String processName = filter.one();
        Map<String, Object> processParams = filter.two();

        //dataMan.flush();

        //-----------------------------------------------------------------------
        //--- insert/update new metadata

        for (RecordInfo ri : records) {

            if (cancelMonitor.get()) {
                return result;
            }

            try {
                String databaseId = metadataUtils.getMetadataId(ri.id);
                if (databaseId == null) {
                    // record doesn't exist (so it doesn't belong to this harvester)
                    log.debug(String.format("Adding record with id %s", ri.id));
                    processParams.put("mdChangeDate", ri.changeDate);
                    addMetadata(t, ri, processName, processParams);
                } else if (localUuids.getID(ri.id) == null) {
                    // Record with such uuid already exists in the database but doesn't belong to this harvester
                    result.datasetUuidExist++;

                    switch (params.getOverrideUuid()) {
                        case OVERRIDE:
                            processParams.put("mdChangeDate", ri.changeDate);
                            updateMetadata(t, ri, Integer.toString(metadataUtils.findOneByUuid(ri.id).getId()),
                                processName, processParams, true);
                            result.updatedMetadata++;
                            break;
                        case RANDOM:
                            if (log.isDebugEnabled()) {
                                log.debug(String.format("Generating random uuid for remote record with uuid %s", ri.id));
                            }
                            String newRandomUuid = UUID.randomUUID().toString();
                            processParams.put("mdChangeDate", ri.changeDate);
                            addMetadata(t, ri, processName, processParams, newRandomUuid);
                            break;
                        case SKIP:
                            log.debug("Skipping record with uuid " + ri.id);
                            result.uuidSkipped++;
                            break;
                        default:
                            //nothing
                    }
                } else {
                    //record exists and belongs to this harvester
                    String id = localUuids.getID(ri.id);
                    processParams.put("mdChangeDate", ri.changeDate);
                    updateMetadata(t, ri, id, processName, processParams, false);
                }
                result.totalMetadata++;
            } catch (Throwable tr) {
                errors.add(new HarvestError(this.context, tr));
                log.error("Unable to process record from OAI (" + this.params.getName() + ")");
                log.error("   Record failed: " + ri.id + ". Error is: " + tr.getMessage());
                log.error(tr);
            } finally {
                result.originalMetadata++;
            }
        }

        metadataIndexer.forceIndexChanges();
        //log.info("End of alignment for : " + params.getName());

        return result;
    }

    public HarvestResult cleanupRemovedRecords(Set<String> records) throws Exception {

        if (cancelMonitor.get()) {
            return result;
        }

        for (String uuid : localUuids.getUUIDs()) {
            if (!records.contains(uuid)) {
                String id = localUuids.getID(uuid);
                log.debug("  - Removing old metadata with local id:" + id);
                metadataManager.deleteMetadata(context, id);
                result.locallyRemoved++;
            }
        }
        metadataIndexer.forceIndexChanges();

        return result;
    }


    protected void addMetadata(XmlRequest t, org.fao.geonet.kernel.harvest.harvester.oaipmh.RecordInfo ri, String processName, Map<String, Object> processParams) throws Exception {
        addMetadata(t, ri, processName, processParams, null);
    }

    protected void addMetadata(XmlRequest t, org.fao.geonet.kernel.harvest.harvester.oaipmh.RecordInfo ri, String processName, Map<String, Object> processParams, String newUuid) throws Exception {
        Element md = Xml.loadString(ri.xml, false); //retrieveMetadata(t, ri);

        if (md == null)
            return;

        //--- schema handled check already done

        String schema = metadataSchemaUtils.autodetectSchema(md);

        if (log.isDebugEnabled()) {
            log.debug("  - Adding metadata with remote id : " + ri.id);
        }


        // Apply the xsl filter chosen by UI
        if (StringUtils.isNotEmpty(params.xslfilter)) {
            md = HarvesterUtil.processMetadata(metadataSchemaUtils.getSchema(schema),
                md, processName, processParams);

            schema = metadataSchemaUtils.autodetectSchema(md);
        }

        //
        // insert metadata
        //
        AbstractMetadata metadata = new Metadata();
        if (newUuid != null) {
            metadata.setUuid(newUuid);
            md = metadataUtils.setUUID(schema, newUuid, md);
        } else {
            metadata.setUuid(ri.id);
        }
        metadata.getDataInfo().
            setSchemaId(schema).
            setRoot(md.getQualifiedName()).
            setType(MetadataType.METADATA).
            setChangeDate(ri.changeDate).
            setCreateDate(ri.changeDate);
        metadata.getSourceInfo().
            setSourceId(params.getUuid()).
            setOwner(getOwner());
        metadata.getHarvestInfo().
            setHarvested(true).
            setUuid(params.getUuid());

        try {
            metadata.getSourceInfo().setGroupOwner(Integer.valueOf(params.getOwnerIdGroup()));
        } catch (NumberFormatException ignored) {
        }

        addCategories(metadata, params.getCategories(), localCateg, context, null, false);

        metadata = metadataManager.insertMetadata(context, metadata, md, IndexingMode.none, false, UpdateDatestamp.NO, false, false);

        String id = String.valueOf(metadata.getId());

        addPrivileges(id, params.getPrivileges(), localGroups, context);

        metadataManager.flush();

        metadataIndexer.indexMetadata(id, Math.random() < 0.01, null);
        result.addedMetadata++;
    }

    protected void updateMetadata(XmlRequest t, org.fao.geonet.kernel.harvest.harvester.oaipmh.RecordInfo ri, String id, String processName, Map<String, Object> processParams, boolean force) throws Exception {
        String date = localUuids.getChangeDate(ri.id);

        if (!force && !ri.isMoreRecentThan(date)) {
            if (log.isDebugEnabled()) {
                log.debug("  - Metadata XML not changed for remote id : " + ri.id);
            }
            result.unchangedMetadata++;
        } else {
            if (log.isDebugEnabled()) {
                log.debug("  - Updating local metadata for remote id : " + ri.id);
            }

            Element md = Xml.loadString(ri.xml, false); //retrieveMetadata(t, ri);

            if (md == null) {
                result.unchangedMetadata++;
                return;
            }

            // The schema of the metadata
            String schema = metadataSchemaUtils.autodetectSchema(md, null);
            boolean updateSchema = false;

            // Apply the xsl filter chosen by UI
            if (StringUtils.isNotEmpty(params.xslfilter)) {
                md = HarvesterUtil.processMetadata(metadataSchemaUtils.getSchema(schema),
                    md, processName, processParams);

                schema = metadataSchemaUtils.autodetectSchema(md);
                updateSchema = true;
            }

            //
            // update metadata
            //
            boolean validate = false;
            boolean ufo = false;
            IndexingMode index = IndexingMode.none;
            String language = context.getLanguage();


            if (updateSchema) {
                MetadataValidationRepository metadataValidationRepository =
                    context.getBean(MetadataValidationRepository.class);

                final String newSchema = schema;
                metadataManager.update(Integer.parseInt(id), entity -> entity.getDataInfo().setSchemaId(newSchema));

                metadataValidationRepository.deleteAll(MetadataValidationSpecs.hasMetadataId(Integer.parseInt(id)));
            }

            final AbstractMetadata metadata = metadataManager.updateMetadata(context, id, md, validate, ufo, language, ri.changeDate.toString(),
                true, index);
            if (force) {
                //change ownership of metadata to new harvester
                metadata.getHarvestInfo().setUuid(params.getUuid());
                metadata.getSourceInfo().setSourceId(params.getUuid());

                metadataManager.save(metadata);
            }

            //--- the administrator could change privileges and categories using the
            //--- web interface so we have to re-set both

            OperationAllowedRepository repository = context.getBean(OperationAllowedRepository.class);
            repository.deleteAllByMetadataId(Integer.parseInt(id));
            addPrivileges(id, params.getPrivileges(), localGroups, context);

            metadata.getCategories().clear();
            addCategories(metadata, params.getCategories(), localCateg, context, null, true);

            metadataManager.flush();
            metadataIndexer.indexMetadata(id, true, null);
            result.updatedMetadata++;
        }
    }
}
