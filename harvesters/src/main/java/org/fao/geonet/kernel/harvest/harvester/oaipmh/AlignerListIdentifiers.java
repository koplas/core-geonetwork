package org.fao.geonet.kernel.harvest.harvester.oaipmh;

import jeeves.server.context.ServiceContext;
import org.apache.commons.lang.StringUtils;
import org.fao.geonet.Logger;
import org.fao.geonet.constants.Geonet;
import org.fao.geonet.domain.AbstractMetadata;
import org.fao.geonet.domain.Metadata;
import org.fao.geonet.domain.MetadataType;
import org.fao.geonet.domain.Pair;
import org.fao.geonet.kernel.UpdateDatestamp;
import org.fao.geonet.kernel.datamanager.IMetadataUtils;
import org.fao.geonet.kernel.harvest.harvester.CategoryMapper;
import org.fao.geonet.kernel.harvest.harvester.GroupMapper;
import org.fao.geonet.kernel.harvest.harvester.HarvestError;
import org.fao.geonet.kernel.harvest.harvester.HarvestResult;
import org.fao.geonet.kernel.harvest.harvester.HarvesterUtil;
import org.fao.geonet.kernel.harvest.harvester.UUIDMapper;
import org.fao.geonet.kernel.search.IndexingMode;
import org.fao.geonet.repository.MetadataValidationRepository;
import org.fao.geonet.repository.OperationAllowedRepository;
import org.fao.geonet.repository.specification.MetadataValidationSpecs;
import org.fao.geonet.utils.Xml;
import org.fao.geonet.utils.XmlRequest;
import org.fao.oaipmh.OaiPmh;
import org.fao.oaipmh.requests.GetRecordRequest;
import org.fao.oaipmh.responses.GetRecordResponse;
import org.jdom.Element;
import org.jdom.JDOMException;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;


public class AlignerListIdentifiers extends Aligner {

    public AlignerListIdentifiers(AtomicBoolean cancelMonitor, ServiceContext sc, OaiPmhParams params, Logger log) {
        super(cancelMonitor, sc, params, log);
    }

    public HarvestResult align(XmlRequest t, Set<RecordInfo> records, Collection<HarvestError> errors) throws Exception {
        log.info("Start of alignment for : " + params.getName());

        //-----------------------------------------------------------------------
        //--- retrieve all local categories and groups
        //--- retrieve harvested uuids for given harvesting node

        localCateg = new CategoryMapper(context);
        localGroups = new GroupMapper(context);
        localUuids = new UUIDMapper(context.getBean(IMetadataUtils.class), params.getUuid());

        Pair<String, Map<String, Object>> filter = HarvesterUtil.parseXSLFilter(params.xslfilter);
        String processName = filter.one();
        Map<String, Object> processParams = filter.two();

        metadataManager.flush();

        //-----------------------------------------------------------------------
        //--- remove old metadata

        for (String uuid : localUuids.getUUIDs()) {

            if (cancelMonitor.get()) {
                return result;
            }

            if (!exists(records, uuid)) {
                String id = localUuids.getID(uuid);

                if (log.isDebugEnabled())
                    log.debug("  - Removing old metadata with local id:" + id);
                metadataManager.deleteMetadataGroup(context, id);

                metadataManager.flush();

                result.locallyRemoved++;
            }
        }
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
                    addMetadata(t, ri, processName, processParams, errors);
                } else if (localUuids.getID(ri.id) == null) {
                    // Record with such uuid already exists in the database but doesn't belong to this harvester
                    result.datasetUuidExist++;

                    switch (params.getOverrideUuid()) {
                        case OVERRIDE:
                            processParams.put("mdChangeDate", ri.changeDate);
                            updateMetadata(t, ri, Integer.toString(metadataUtils.findOneByUuid(ri.id).getId()),
                                processName, processParams, true, errors);
                            result.updatedMetadata++;
                            break;
                        case RANDOM:
                            if (log.isDebugEnabled()) {
                                log.debug(String.format("Generating random uuid for remote record with uuid %s", ri.id));
                            }
                            String newRandomUuid = UUID.randomUUID().toString();
                            processParams.put("mdChangeDate", ri.changeDate);
                            addMetadata(t, ri, processName, processParams, newRandomUuid, errors);
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
                    updateMetadata(t, ri, id, processName, processParams, false, errors);
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
        log.info("End of alignment for : " + params.getName());

        return result;
    }

    private void addMetadata(XmlRequest t, RecordInfo ri, String processName, Map<String, Object> processParams, Collection<HarvestError> errors) throws Exception {
        addMetadata(t, ri, processName, processParams, null, errors);
    }

    private void addMetadata(XmlRequest t, RecordInfo ri, String processName, Map<String, Object> processParams, String newUuid, Collection<HarvestError> errors) throws Exception {
        Element md = retrieveMetadata(t, ri, errors);

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

        metadataIndexer.indexMetadata(id, true, null);
        result.addedMetadata++;
    }

    private void updateMetadata(XmlRequest t, RecordInfo ri, String id, String processName, Map<String, Object> processParams, boolean force, Collection<HarvestError> errors) throws Exception {
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

            Element md = retrieveMetadata(t, ri, errors);

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

    private Element retrieveMetadata(XmlRequest transport, RecordInfo ri, Collection<HarvestError> errors) {
        try {
            if (log.isDebugEnabled()) log.debug("  - Getting remote metadata with id : " + ri.id);

            GetRecordRequest req = new GetRecordRequest(transport);
            req.setSchemaPath(context.getAppPath().resolve(Geonet.SchemaPath.OAI_PMH));

            req.setIdentifier(ri.id);
            req.setMetadataPrefix(ri.prefix);

            GetRecordResponse res = req.execute();

            Element md = res.getRecord().getMetadata();

            if (log.isDebugEnabled()) log.debug("    - Record got:\n" + Xml.getString(md));

            if (isOaiDc(md)) {
                if (log.isDebugEnabled()) log.debug("    - Converting oai_dc to dublin core");
                md = toDublinCore(md, errors);

                if (md == null)
                    return null;
            }

            String schema = metadataSchemaUtils.autodetectSchema(md, null);

            if (schema == null) {
                log.warning("Skipping metadata with unknown schema. Remote id : " + ri.id);
                result.unknownSchema++;
            } else {
                try {
                    Integer groupIdVal = null;
                    if (StringUtils.isNotEmpty(params.getOwnerIdGroup())) {
                        groupIdVal = Integer.parseInt(params.getOwnerIdGroup());
                    }
                    metadataValidator.validateExternalMetadata(schema, md, context, " ", groupIdVal);
                    return (Element) md.detach();
                } catch (Exception e) {
                    log.info("Skipping metadata that does not validate. Remote id : " + ri.id);
                    result.doesNotValidate++;
                }
            }
        }

        catch(JDOMException e) {
            HarvestError harvestError = new HarvestError(context, e);
            harvestError.setDescription("Skipping metadata with bad XML format. Remote id : "+ ri.id);
            harvestError.printLog();
            errors.add(harvestError);
            result.badFormat++;
        }

        catch(Exception e)
        {
            HarvestError harvestError = new HarvestError(context, e);
            harvestError.setDescription("Raised exception while getting metadata file : "+ e);
            errors.add(harvestError);
            harvestError.printLog();
            result.unretrievable++;
        }

        //--- we don't raise any exception here. Just try to go on
        return null;
    }

    private boolean isOaiDc(Element md) {
        return (md.getName().equals("dc")) && (md.getNamespace().equals(OaiPmh.Namespaces.OAI_DC));
    }

    private Element toDublinCore(Element md, Collection<HarvestError> errors) {
        Path styleSheet = context.getAppPath().resolve("conversion/oai_dc-to-dublin-core/main.xsl");

        try
        {
            return Xml.transform(md, styleSheet);
        }
        catch (Exception e)
        {
            HarvestError harvestError = new HarvestError(context, e);
            harvestError.setDescription("Cannot convert oai_dc to dublin core : "+ e);
            errors.add(harvestError);
            harvestError.printLog();
            return null;
        }
    }
}
