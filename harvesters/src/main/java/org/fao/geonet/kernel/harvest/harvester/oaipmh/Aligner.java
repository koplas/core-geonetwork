package org.fao.geonet.kernel.harvest.harvester.oaipmh;


import jeeves.server.context.ServiceContext;
import org.fao.geonet.GeonetContext;
import org.fao.geonet.Logger;
import org.fao.geonet.constants.Geonet;
import org.fao.geonet.kernel.AccessManager;
import org.fao.geonet.kernel.datamanager.IMetadataIndexer;
import org.fao.geonet.kernel.datamanager.IMetadataManager;
import org.fao.geonet.kernel.datamanager.IMetadataSchemaUtils;
import org.fao.geonet.kernel.datamanager.IMetadataUtils;
import org.fao.geonet.kernel.datamanager.IMetadataValidator;
import org.fao.geonet.kernel.harvest.BaseAligner;
import org.fao.geonet.kernel.harvest.harvester.CategoryMapper;
import org.fao.geonet.kernel.harvest.harvester.GroupMapper;
import org.fao.geonet.kernel.harvest.harvester.HarvestResult;
import org.fao.geonet.kernel.harvest.harvester.UUIDMapper;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;


public abstract class Aligner extends BaseAligner<OaiPmhParams> {
    protected ServiceContext context;
    protected AccessManager accessManager;
    protected CategoryMapper localCateg;
    protected GroupMapper localGroups;
    protected UUIDMapper localUuids;

    protected IMetadataUtils metadataUtils;
    protected IMetadataSchemaUtils metadataSchemaUtils;
    protected IMetadataValidator metadataValidator;
    protected IMetadataManager metadataManager;
    protected IMetadataIndexer metadataIndexer;

    protected HarvestResult result;

    protected Logger log;

    public Aligner(AtomicBoolean cancelMonitor, ServiceContext sc, OaiPmhParams params, Logger log) {
        super(cancelMonitor);
        this.params = params;
        this.log = log;
        this.context = sc;

        GeonetContext gc = (GeonetContext) context.getHandlerContext(Geonet.CONTEXT_NAME);
        accessManager = gc.getBean(AccessManager.class);

        metadataUtils = gc.getBean(IMetadataUtils.class);
        metadataSchemaUtils = gc.getBean(IMetadataSchemaUtils.class);
        metadataValidator = gc.getBean(IMetadataValidator.class);

        metadataManager = gc.getBean(IMetadataManager.class);
        metadataIndexer = gc.getBean(IMetadataIndexer.class);
        result = new HarvestResult();
        result.unretrievable = 0;
        result.uuidSkipped = 0;
        result.couldNotInsert = 0;
    }

    protected boolean exists(Set<RecordInfo> records, String uuid) {
        for (RecordInfo ri : records)
            if (uuid.equals(ri.id))
                return true;

        return false;
    }
}
