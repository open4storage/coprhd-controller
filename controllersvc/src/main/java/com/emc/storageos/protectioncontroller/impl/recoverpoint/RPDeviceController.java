/**
* Copyright 2015 EMC Corporation
* All Rights Reserved
 */
/**
 * Copyright (c) 2013 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */

package com.emc.storageos.protectioncontroller.impl.recoverpoint;

import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.emc.fapiclient.ws.FunctionalAPIActionFailedException_Exception;
import com.emc.fapiclient.ws.FunctionalAPIInternalError_Exception;
import com.emc.storageos.blockorchestrationcontroller.BlockOrchestrationInterface;
import com.emc.storageos.blockorchestrationcontroller.VolumeDescriptor;
import com.emc.storageos.coordinator.client.service.CoordinatorClient;
import com.emc.storageos.coordinator.exceptions.CoordinatorException;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.constraint.AlternateIdConstraint;
import com.emc.storageos.db.client.constraint.Constraint;
import com.emc.storageos.db.client.constraint.ContainmentConstraint;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.BlockConsistencyGroup;
import com.emc.storageos.db.client.model.BlockConsistencyGroup.Types;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.BlockSnapshot;
import com.emc.storageos.db.client.model.BlockSnapshot.TechnologyType;
import com.emc.storageos.db.client.model.DataObject.Flag;
import com.emc.storageos.db.client.model.DiscoveredDataObject.RegistrationStatus;
import com.emc.storageos.db.client.model.ExportGroup;
import com.emc.storageos.db.client.model.ExportGroup.ExportGroupType;
import com.emc.storageos.db.client.model.ExportMask;
import com.emc.storageos.db.client.model.Initiator;
import com.emc.storageos.db.client.model.NamedURI;
import com.emc.storageos.db.client.model.OpStatusMap;
import com.emc.storageos.db.client.model.Operation;
import com.emc.storageos.db.client.model.Project;
import com.emc.storageos.db.client.model.ProtectionSet;
import com.emc.storageos.db.client.model.ProtectionSet.ProtectionStatus;
import com.emc.storageos.db.client.model.ProtectionSystem;
import com.emc.storageos.db.client.model.StoragePort;
import com.emc.storageos.db.client.model.StorageProtocol;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.VirtualArray;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.Volume.PersonalityTypes;
import com.emc.storageos.db.client.model.util.BlockConsistencyGroupUtils;
import com.emc.storageos.db.client.util.CustomQueryUtility;
import com.emc.storageos.db.client.util.NameGenerator;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.db.exceptions.DatabaseException;
import com.emc.storageos.exceptions.DeviceControllerErrors;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.exceptions.DeviceControllerExceptions;
import com.emc.storageos.locking.LockTimeoutValue;
import com.emc.storageos.locking.LockType;
import com.emc.storageos.model.ResourceOperationTypeEnum;
import com.emc.storageos.protectioncontroller.RPController;
import com.emc.storageos.recoverpoint.exceptions.RecoverPointException;
import com.emc.storageos.recoverpoint.impl.RecoverPointClient;
import com.emc.storageos.recoverpoint.objectmodel.RPBookmark;
import com.emc.storageos.recoverpoint.objectmodel.RPConsistencyGroup;
import com.emc.storageos.recoverpoint.objectmodel.RPSite;
import com.emc.storageos.recoverpoint.requests.CGRequestParams;
import com.emc.storageos.recoverpoint.requests.CreateBookmarkRequestParams;
import com.emc.storageos.recoverpoint.requests.CreateCopyParams;
import com.emc.storageos.recoverpoint.requests.CreateRSetParams;
import com.emc.storageos.recoverpoint.requests.CreateVolumeParams;
import com.emc.storageos.recoverpoint.requests.MultiCopyDisableImageRequestParams;
import com.emc.storageos.recoverpoint.requests.MultiCopyEnableImageRequestParams;
import com.emc.storageos.recoverpoint.requests.MultiCopyRestoreImageRequestParams;
import com.emc.storageos.recoverpoint.requests.RPCopyRequestParams;
import com.emc.storageos.recoverpoint.requests.RecreateReplicationSetRequestParams;
import com.emc.storageos.recoverpoint.responses.CreateBookmarkResponse;
import com.emc.storageos.recoverpoint.responses.GetBookmarksResponse;
import com.emc.storageos.recoverpoint.responses.MultiCopyDisableImageResponse;
import com.emc.storageos.recoverpoint.responses.MultiCopyEnableImageResponse;
import com.emc.storageos.recoverpoint.responses.MultiCopyRestoreImageResponse;
import com.emc.storageos.recoverpoint.responses.RecoverPointCGResponse;
import com.emc.storageos.recoverpoint.responses.RecoverPointStatisticsResponse;
import com.emc.storageos.recoverpoint.responses.RecoverPointVolumeProtectionInfo;
import com.emc.storageos.security.audit.AuditLogManager;
import com.emc.storageos.services.OperationTypeEnum;
import com.emc.storageos.svcs.errorhandling.model.ServiceCoded;
import com.emc.storageos.svcs.errorhandling.model.ServiceError;
import com.emc.storageos.svcs.errorhandling.resources.InternalException;
import com.emc.storageos.util.ConnectivityUtil;
import com.emc.storageos.util.NetworkLite;
import com.emc.storageos.volumecontroller.AsyncTask;
import com.emc.storageos.volumecontroller.BlockController;
import com.emc.storageos.volumecontroller.BlockStorageDevice;
import com.emc.storageos.volumecontroller.ControllerException;
import com.emc.storageos.volumecontroller.ControllerLockingService;
import com.emc.storageos.volumecontroller.StorageController;
import com.emc.storageos.volumecontroller.TaskCompleter;
import com.emc.storageos.volumecontroller.impl.ControllerLockingUtil;
import com.emc.storageos.volumecontroller.impl.ControllerUtils;
import com.emc.storageos.volumecontroller.impl.NativeGUIDGenerator;
import com.emc.storageos.volumecontroller.impl.block.BlockDeviceController;
import com.emc.storageos.volumecontroller.impl.block.ExportWorkflowUtils;
import com.emc.storageos.volumecontroller.impl.block.MaskingOrchestrator;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.AuditBlockUtil;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotActivateCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotCreateCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotDeactivateCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotDeleteCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotRestoreCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.ExportOrchestrationTask;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.RPCGCreateCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.RPCGExportCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.RPCGExportDeleteCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.RPCGExportOrchestrationCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.RPCGProtectionTaskCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.TaskLockingCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.VolumeWorkflowCompleter;
import com.emc.storageos.volumecontroller.impl.monitoring.RecordableEventManager;
import com.emc.storageos.volumecontroller.impl.plugins.RPStatisticsHelper;
import com.emc.storageos.volumecontroller.placement.BlockStorageScheduler;
import com.emc.storageos.volumecontroller.placement.StoragePortsAssigner;
import com.emc.storageos.volumecontroller.placement.StoragePortsAssignerFactory;
import com.emc.storageos.workflow.Workflow;
import com.emc.storageos.workflow.WorkflowException;
import com.emc.storageos.workflow.WorkflowService;
import com.emc.storageos.workflow.WorkflowStepCompleter;
import com.google.common.base.Joiner;

/**
 * RecoverPoint specific protection controller implementation.
 */
public class RPDeviceController implements RPController, BlockOrchestrationInterface, MaskingOrchestrator {
	
	// RecoverPoint consistency group name prefix
	private static final String CG_NAME_PREFIX = "ViPR-";
	private static final String VIPR_SNAPSHOT_PREFIX = "ViPR-snapshot-";
	
    // Various steps for workflows
    private static final String STEP_CG_CREATION 				= "cgCreation";
    private static final String STEP_CG_UPDATE = "cgUpdate";
    private static final String STEP_EXPORT_GROUP 				= "exportGroup";
    private static final String STEP_DV_REMOVE_CG 				= "dvRemoveCG";
    private static final String STEP_DV_REMOVE_VOLUME_EXPORT 	= "dvRemoveVolumeExport";
    private static final String STEP_DV_CLEANUP 				= "dvDeleteCleanup";
    private static final String STEP_ENABLE_IMAGE_ACCESS 		= "enableImageAccess";
    private static final String STEP_DISABLE_IMAGE_ACCESS		= "disableImageAccess";
    private static final String STEP_EXPORT_DELETE_SNAPSHOT 	= "exportDeleteSnapshot";
    private static final String STEP_EXPORT_GROUP_DELETE 		= "exportGroupDelete";
    private static final String STEP_EXPORT_GROUP_DISABLE 		= "exportGroupDisable";
    private static final String STEP_EXPORT_REMOVE_SNAPSHOT 	= "exportRemoveSnapshot";
	private static final String STEP_POST_VOLUME_CREATE 		= "postVolumeCreate";
	
	private static final String STEP_PRE_VOLUME_EXPAND 			= "preVolumeExpand";
	private static final String STEP_POST_VOLUME_EXPAND 		= "postVolumeExpand";
	
    // Methods in the create workflow.  Constants helps us avoid step dependency flubs.
    private static final String METHOD_CG_CREATE_STEP = "cgCreateStep";
    private static final String METHOD_CG_CREATE_ROLLBACK_STEP = "cgCreateRollbackStep";
 
    // Methods in the update workflow. 
    private static final String METHOD_CG_UPDATE_STEP = "cgUpdateStep";
    private static final String METHOD_CG_UPDATE_ROLLBACK_STEP = "cgUpdateRollbackStep";

    // Methods in the delete workflow.
    private static final String METHOD_DELETE_CG_STEP 			= "cgDeleteStep";

    // Methods in the export group create workflow
    private static final String METHOD_ENABLE_IMAGE_ACCESS_STEP = "enableImageAccessStep";
    private static final String METHOD_ENABLE_IMAGE_ACCESS_ROLLBACK_STEP = "enableImageAccessStepRollback";

    // Methods in the export group delete workflow
    private static final String METHOD_DISABLE_IMAGE_ACCESS_STEP = "disableImageAccessStep";

    // Methods in the export group remove volume workflow
    private static final String METHOD_DISABLE_IMAGE_ACCESS_SINGLE_STEP = "disableImageAccessSingleStep";

    // Methods in the expand volume workflow
	private static final String METHOD_DELETE_RSET_STEP 		= "deleteRSetStep";
	private static final String METHOD_RECREATE_RSET_STEP		= "recreateRSetStep";
	
	// Methods in the create RP snapshot workflow
	private static final String STEP_BOOKMARK_CREATE 			= "createBookmark";
	private static final String METHOD_CREATE_BOOKMARK_STEP 	= "createBookmarkStep";
	private static final String METHOD_ROLLBACK_CREATE_BOOKMARK_STEP 	= "createBookmarkRollbackStep";
	
	private static final String STEP_CREATE_BLOCK_SNAPSHOT 		= "createBlockSnapshot";
	
	
	private static final String METHOD_CREATE_BLOCK_SNAPSHOT_STEP = "createBlockSnapshotStep";
	private static final String METHOD_ROLLBACK_CREATE_BLOCK_SNAPSHOT 		= "createBlockSnapshotRollbackStep";
	 private static final String METHOD_SNAPSHOT_DISABLE_IMAGE_ACCESS_SINGLE_STEP = "snapshotDisableImageAccessSingleStep";
	
	
	// Method to clean 
	private static final String METHOD_RP_VPLEX_REINSTATE_SRC_VVOL_STEP = "rpVPlexReinstateSourceVirtualVolumeStep";

    protected final static String CONTROLLER_SVC = "controllersvc";
    protected final static String CONTROLLER_SVC_VER = "1";
    private static final Logger _log = LoggerFactory.getLogger(RPDeviceController.class);

    private static final String EVENT_SERVICE_TYPE = "rp controller";
    private static final String EVENT_SERVICE_SOURCE = "RPDeviceController";

	private static final String METHOD_EXPORT_ORCHESTRATE_STEP = "exportOrchestrationSteps";
	private static final String METHOD_EXPORT_ORCHESTRATE_ROLLBACK_STEP = "exportOrchestrationRollbackSteps";
	private static final String STEP_EXPORT_ORCHESTRATION = "exportOrchestration";

	private static final String EXPORT_ORCHESTRATOR_WF_NAME = "RP_EXPORT_ORCHESTRATION_WORKFLOW";
 
    private static DbClient      _dbClient;
    protected CoordinatorClient _coordinator;
    private Map<String, BlockStorageDevice> _devices;
    private NameGenerator _nameGenerator;
    private WorkflowService _workflowService;
    private RPHelper _rpHelper;
    private ExportWorkflowUtils _exportWfUtils;
    private RPStatisticsHelper _rpStatsHelper;
    private RecordableEventManager _eventManager;
    private ControllerLockingService _locker;
    
    @Autowired
    private AuditLogManager _auditMgr;     
    
    /* Inner class for handling exports for RP */
    private class RPExport {
        private URI storageSystem;
        private String rpSite;
        private URI varray;
        private List<URI> volumes;
        
        public RPExport() {            
        }
        
        public RPExport(URI storageSystem, String rpSite, URI varray) {
            this.storageSystem = storageSystem;
            this.rpSite = rpSite;
            this.varray = varray;
        }
        
        public URI getStorageSystem() {
            return storageSystem;
        }
        
        public void setStorageSystem(URI storageSystem) {
            this.storageSystem = storageSystem;
        }
        
        public String getRpSite() {
            return rpSite;
        }
        
        public void setRpSite(String rpSite) {
            this.rpSite = rpSite;
        }
        
        public URI getVarray() {
            return varray;
        }
        
        public void setVarray(URI varray) {
            this.varray = varray;
        }
        
        public List<URI> getVolumes() {
            if (volumes == null) {
                volumes = new ArrayList<URI>();
            }
            return volumes;
        }

        public void setVolumes(List<URI> volumes) {
            this.volumes = volumes;
        }
        
        @Override
        public String toString() {
            return "RPExport [storageSystem=" + storageSystem.toString() + ", rpSite="
                    + rpSite + ", varray=" + varray.toString() + "]";
        }
    }
       
    public void setLocker(ControllerLockingService locker) {
    	this._locker = locker;
    }
    
    public RPStatisticsHelper getRpStatsHelper() {
        return _rpStatsHelper;
    }

    public void setRpStatsHelper(RPStatisticsHelper rpStatsHelper) {
        this._rpStatsHelper = rpStatsHelper;
    }
    
    public void setEventManager(RecordableEventManager eventManager) {
        _eventManager = eventManager;
    }

    public void setRpHelper(RPHelper rpHelper) {
        _rpHelper = rpHelper;
    }

    public void setCoordinator(CoordinatorClient locator) {
        _coordinator = locator;
    }

    public void setDevices(Map<String, BlockStorageDevice> deviceInterfaces) {
        _devices = deviceInterfaces;
    }

    public void setDbClient(DbClient dbClient) {
        _dbClient = dbClient;
    }

    private BlockStorageDevice getDevice(String deviceType) {
        return _devices.get(deviceType);
    }

    public void setWorkflowService(WorkflowService workflowService) {
        this._workflowService = workflowService;
    }

    public void setExportWorkflowUtils(ExportWorkflowUtils exportWorkflowUtils) {
        _exportWfUtils = exportWorkflowUtils;
    }

    public NameGenerator getNameGenerator() {
        return _nameGenerator;
    }
    
    public void setNameGenerator(NameGenerator _nameGenerator) {
        this._nameGenerator = _nameGenerator;
    }   

    @Override
    public void connect(URI systemId) throws InternalException {
    	_log.debug("BEGIN RPDeviceController.connect()");
    	ProtectionSystem rpSystem = null;
		rpSystem = _dbClient.queryObject(ProtectionSystem.class, systemId);

		// Verify non-null storage device returned from the database client.
		if (rpSystem == null) {
		    throw DeviceControllerExceptions.recoverpoint.failedConnectingForMonitoring(systemId);
		}

		RecoverPointClient rp = RPHelper.getRecoverPointClient(rpSystem);
		rp.ping();
		_log.debug("END RPDeviceController.connect()");
    }

    @Override
    public void disconnect(URI systemId) throws InternalException {
    	_log.info("BEGIN RecoverPointProtection.disconnectStorage()");
    	// Retrieve the storage device info from the database.
    	ProtectionSystem protectionObj = null;
		protectionObj = _dbClient.queryObject(ProtectionSystem.class, systemId);
		// Verify non-null storage device returned from the database client.
		if (protectionObj == null) {
		    throw DeviceControllerExceptions.recoverpoint.failedConnectingForMonitoring(systemId);
		}

		_log.info("END RecoverPointProtection.disconnectStorage()");
    }
    
	@Override
	public String addStepsForCreateVolumes(Workflow workflow, String waitFor,
			List<VolumeDescriptor> volumeDescriptors, String taskId)
			throws InternalException {

		// Just grab a legit target volume that already has an assigned protection controller.  
		// This will work for all operations, adding, removing, vpool change, etc.
        List<VolumeDescriptor> protectionControllerDescriptors = VolumeDescriptor.filterByType(volumeDescriptors, 
                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.RP_TARGET,  
        									  VolumeDescriptor.Type.RP_VPLEX_VIRT_TARGET,
        									  VolumeDescriptor.Type.RP_EXISTING_PROTECTED_SOURCE },
                new VolumeDescriptor.Type[] { });
        // If there are no RP volumes, just return
        if (protectionControllerDescriptors.isEmpty()) {
            _log.info("No RP Steps required");
            return waitFor;
        }
        
        _log.info("Adding RP steps for create volumes");
        // Grab any volume from the list so we can grab the protection system, which will be the same for all volumes.
    	Volume volume = _dbClient.queryObject(Volume.class, protectionControllerDescriptors.get(0).getVolumeURI());
		ProtectionSystem rpSystem = _dbClient.queryObject(ProtectionSystem.class, volume.getProtectionController());

		// Get only the RP volumes from the descriptors.
        List<VolumeDescriptor> volumeDescriptorsTypeFilter = VolumeDescriptor.filterByType(volumeDescriptors, 
                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.RP_SOURCE, 
        									  VolumeDescriptor.Type.RP_JOURNAL, 
        		                              VolumeDescriptor.Type.RP_TARGET, 
        		                              VolumeDescriptor.Type.RP_EXISTING_SOURCE,
        		                              VolumeDescriptor.Type.RP_EXISTING_PROTECTED_SOURCE,
        		                              VolumeDescriptor.Type.RP_VPLEX_VIRT_SOURCE,
        		                              VolumeDescriptor.Type.RP_VPLEX_VIRT_TARGET,
											  VolumeDescriptor.Type.RP_VPLEX_VIRT_JOURNAL }, 
                new VolumeDescriptor.Type[] { });
        // If there are no RP volumes, just return
        if (volumeDescriptorsTypeFilter.isEmpty()) return waitFor;
       		
        try {
        				
			List<VolumeDescriptor> existingProtectedSourceDescriptors = VolumeDescriptor.filterByType(volumeDescriptors, 
	                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.RP_EXISTING_PROTECTED_SOURCE },
	                new VolumeDescriptor.Type[] { });
			
			boolean executeCreateSteps = true;
	        if (!existingProtectedSourceDescriptors.isEmpty()) {
	            executeCreateSteps = false;
	        }
	       
	        addExportVolumesSteps(workflow, volumeDescriptorsTypeFilter, waitFor, rpSystem, taskId);
	        
	        // Handle creation or updating of the Consistency Group (moved from the Export Workflow)
	        // Get the CG Params based on the volume descriptors
            CGRequestParams params = this.getCGRequestParams(volumeDescriptors, rpSystem);
            updateCGParams(params);
	        
	        if (executeCreateSteps) {                
    		    _log.info("Adding steps for Create CG...");               
                addCreateCGStep(workflow, volumeDescriptors, params, rpSystem, taskId);
                addPostVolumeCreateSteps(workflow, volumeDescriptors, rpSystem, taskId);                
    		}
    		else {
    		    _log.info("Adding steps for Update CG...");    		    
                addUpdateCGStep(workflow, volumeDescriptors, params, rpSystem, taskId);                
    		}

	        
		} catch (Exception e) {
			doFailAddStep(volumeDescriptorsTypeFilter, taskId, e);
			throw e;
		}

        return STEP_POST_VOLUME_CREATE;
	}

	/**
	 * Adds any post volume create steps that are needed.
	 * 
	 * @param workflow
	 * @param volumeDescriptors
	 * @param rpSystem
	 * @param taskId
	 */
	private void addPostVolumeCreateSteps(Workflow workflow, List<VolumeDescriptor> volumeDescriptors, ProtectionSystem rpSystem, String taskId) {
	    
	    // Post Volume Create Step 1: RP VPlex reinstate Virtual Volume to original request.
	    List<VolumeDescriptor> rpVPlexSourceDescriptor = VolumeDescriptor.filterByType(volumeDescriptors, 
                                                                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.RP_VPLEX_VIRT_SOURCE }, 
                                                                new VolumeDescriptor.Type[] { });
	    if (rpVPlexSourceDescriptor != null &&
	            !rpVPlexSourceDescriptor.isEmpty()) {
	        String stepId = workflow.createStepId();
            Workflow.Method rpVPlexRestoreSourceVirtualVolumeMethod = new Workflow.Method(METHOD_RP_VPLEX_REINSTATE_SRC_VVOL_STEP,
                                                                                        rpVPlexSourceDescriptor);
        
            workflow.createStep(STEP_POST_VOLUME_CREATE, "RP VPlex reinstate Virtual Volume to original request",
                                STEP_CG_CREATION, rpSystem.getId(), rpSystem.getSystemType(), this.getClass(),
                                rpVPlexRestoreSourceVirtualVolumeMethod, null, stepId);
	    }
    }
	
	/**
	 * WF Step to reinstate the RP VPLEX Source Virtual Volume to use the originally requested assets.
	 * 
	 * With RP+VPLEX there is an option when the user adds High Availability to the Source VPool 
     * to use the HA VArray (and optionally an HA VPool) as the RecoverPoint Source. 
     * Meaning the HA VArray should be used for connectivity to RP and not the Source VArray.
     * 
     * During RP+VPLEX placement we perform a "swap" in the backend so that Source becomes HA and 
     * HA becomes Source.
     * 
     * After the VPlex Virtual Volume is created we want to reverse the swap back to the original
     * request VPool/VArray for clarity purposes for the user. (i.e. we want the Virtual Volume
     * to show that it was created with the requested VPool and VArray).
     * 
     * So from the backing volumes, try and find the original VPool and VArray that were used
     * for the volume create request. We can use that volume to update the VPlex Virtual
     * Volume.
	 * 
	 * @param rpVPlexVolumeDescriptors Descriptors for RP_VPLEX_VIRT_SOURCE volumes  
	 * @param token Workflow step ID
	 * @return Whether or not the operation succeeded
	 * @throws InternalException
	 */
	public boolean rpVPlexReinstateSourceVirtualVolumeStep(List<VolumeDescriptor> rpVPlexVolumeDescriptors, String token) throws InternalException { 
	    try {	            	            
	        WorkflowStepCompleter.stepExecuting(token);

	        for (VolumeDescriptor volumeDescriptor : rpVPlexVolumeDescriptors) {
	            Volume srcVolume = _dbClient.queryObject(Volume.class, volumeDescriptor.getVolumeURI());
	            // We're only concerned with the RP VPLEX Source Virtual Volume if it's VPLEX Distributed 
                if (srcVolume != null 
                        && srcVolume.getAssociatedVolumes() != null	                                                
                        && srcVolume.getAssociatedVolumes().size() >= 2) {
                    // Find the volume with the original requested assets (original Virtual Pool and Virtual Array)
                    Volume volWithOriginalAssets = findRPVPlexVolumeWithOrginalAssets(srcVolume.getAssociatedVolumes());
                    if (volWithOriginalAssets != null) {
                        _log.info("Updating RP VPLEX Source Virtual Volume [" + srcVolume.getLabel() 
                                + "] with the original requested assets (original Virtual Pool and Virtual Array)");
                        // Update the Virtual Volume with the original assets.
                        srcVolume.setVirtualArray(volWithOriginalAssets.getVirtualArray());
                        srcVolume.setVirtualPool(volWithOriginalAssets.getVirtualPool());
                        _dbClient.persistObject(srcVolume);
                    }
                }               
            }
                           	            
	        // Update the workflow state.
	        WorkflowStepCompleter.stepSucceded(token);
	        _log.info(METHOD_RP_VPLEX_REINSTATE_SRC_VVOL_STEP + " is complete.");
	        
        } catch (Exception e) {            
            stepFailed(token, e, METHOD_RP_VPLEX_REINSTATE_SRC_VVOL_STEP);
            return false;
        }
     
        return true;
    }
	 
	/**
     * Find the volume with the original requested assets (original Virtual Pool and Virtual Array)
     * and make sure that the RP VPLEX Source Virtual Volume has those set. This is what is reflected
     * in the UI. The reason that they could be different is because of the possibility that the 
     * user chose to use the HA Virtual Pool / Virtual Array as the leg connected to RP.
     * 
     * @param backingVolumes backing volumes of the VPlex Virtual Volume passed in
     * @return Volume that has the original Virtual Assets from the volume create request
     */
    private Volume findRPVPlexVolumeWithOrginalAssets(StringSet backingVolumeURIs) {
        Volume volWithOriginalAssets = null;
        for (String backingVolumeURI : backingVolumeURIs) {
            Volume backingVolume = _dbClient.queryObject(Volume.class, URI.create(backingVolumeURI));
            if (backingVolume != null && backingVolume.getVirtualPool() != null) {
                VirtualPool vpool = _dbClient.queryObject(VirtualPool.class, backingVolume.getVirtualPool());
                // Only an RP+VPLEX Source VPool will have getHaVarrayConnectedToRp() set. 
                // We can then check to see if the value of getHaVarrayConnectedToRp()
                // is the Source VArray of the volume we're looking at. 
                if (vpool != null 
                        && vpool.getHaVarrayConnectedToRp() != null                     
                        && !vpool.getHaVarrayConnectedToRp().isEmpty()
                        && !vpool.getHaVarrayConnectedToRp().equals(NullColumnValueGetter.getNullStr())
                        && !vpool.getHaVarrayConnectedToRp().equals(backingVolume.getVirtualArray().toString())
                        && VirtualPool.HighAvailabilityType.vplex_distributed.name()
                            .equals(vpool.getHighAvailability())) {
                    // This backing volume has the original VPool and VArray from the request, 
                    // let's return it.                
                    volWithOriginalAssets = backingVolume;
                    break;
                }
            }
        }
        
        return volWithOriginalAssets;
    }

    private void doFailAddStep(List<VolumeDescriptor> volumeDescriptors,
			String taskId, Exception e)
			        throws InternalException {
        final List<URI> volumeURIs = getVolumeURIs(volumeDescriptors);
        final TaskLockingCompleter completer = new RPCGCreateCompleter(volumeURIs, taskId);
        _log.error("Could not create protection for RecoverPoint on volumes: " + volumeURIs, e);
        final ServiceCoded error;
        if (e instanceof ServiceCoded) {
            error = (ServiceCoded) e;
        } else {
            error = DeviceControllerErrors.recoverpoint
                    .couldNotCreateProtectionOnVolumes(volumeURIs);
        }
        _log.error(error.getMessage());
        completer.error(_dbClient, _locker, error);
	}

	private List<URI> getVolumeURIs(List<VolumeDescriptor> volumeDescriptors) {
		List<URI> volumeURIs = new ArrayList<URI>();
		for (VolumeDescriptor volumeDescriptor : volumeDescriptors) {
			volumeURIs.add(volumeDescriptor.getVolumeURI());						
		}
		return volumeURIs;
	}

	@Override
	public String addStepsForDeleteVolumes(Workflow workflow, String waitFor,
			List<VolumeDescriptor> volumes, String taskId)
			throws InternalException {
        // Filter to get only the RP volumes.
        List<VolumeDescriptor> rpVolumes = VolumeDescriptor.filterByType(volumes, 
                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.RP_SOURCE,
        										VolumeDescriptor.Type.RP_VPLEX_VIRT_SOURCE}, 
                new VolumeDescriptor.Type[] { });
        // If there are no RP volumes, just return
        if (rpVolumes.isEmpty()) return waitFor;

		// Task 1: If this is the last volume, remove the consistency group
        waitFor = addDeleteCGStep(workflow, waitFor, rpVolumes);

        // Tasks 2: Remove the volumes from the export group
        return addExportRemoveVolumesSteps(workflow, waitFor, rpVolumes);
	}

	@Override
	public String addStepsForPostDeleteVolumes(Workflow workflow,
			String waitFor, List<VolumeDescriptor> volumes, String taskId, VolumeWorkflowCompleter completer) throws InternalException {
        // Filter to get only the RP volumes.
        List<VolumeDescriptor> rpVolumes = VolumeDescriptor.filterByType(volumes, 
                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.RP_SOURCE,
        										VolumeDescriptor.Type.RP_VPLEX_VIRT_SOURCE}, 
                new VolumeDescriptor.Type[] { });
        // If there are no RP volumes, just return
        if (rpVolumes.isEmpty()) return waitFor;

    	// Lock the CG (no-op for non-CG)
        // http://lglah169.lss.emc.com/r/6348/
        // May be more appropriate in block orchestrator's deleteVolume, but I preferred it here
        // to keep it closer to the feature it locks and the service codes that are produced when
        // the lock fails.
        lockCG(completer);

        return STEP_DV_CLEANUP;
	}
	
	@Override
    public String addStepsForChangeVirtualPool(Workflow workflow, String waitFor,
            List<VolumeDescriptor> volumeDescriptors, String taskId) throws InternalException {	

        return addStepsForCreateVolumes(workflow, waitFor, volumeDescriptors, taskId);
    }

	/**
     * Create the RP Client consistency group request object based on the incoming prepared volumes.
     * 
     * @param volumeDescriptors volume descriptor objects
	 * @param rpSystem 
     * @return RP request to create CG
     * @throws DatabaseException
     */
    private CGRequestParams getCGRequestParams(List<VolumeDescriptor> volumeDescriptors, ProtectionSystem rpSystem) throws DatabaseException {
        _log.info("Creating CG Request param...");
        
    	// Maps of replication set request objects, where the key is the rset name itself
    	Map<String, CreateRSetParams> rsetParamsMap = new HashMap<String, CreateRSetParams>();
    	// Maps of the copy request objects, where the key is the copy name itself
    	Map<String, CreateCopyParams> copyParamsMap = new HashMap<String, CreateCopyParams>();

    	// The parameters we need at the CG Level that we can only get from looking at the Volumes
    	Project project = null;
    	String cgName = null;
    	Set<String> productionCopies = new HashSet<String>();
    	BlockConsistencyGroup cg = null;
    	String copyMode = null;
    	String rpoType = null;
    	Long rpoValue = null;
    	   	
    	Map<URI, Volume> volumeMap = new HashMap<URI, Volume>();
    	
    	// Sort the volume descriptors using the natural order of the enum.
    	// In this case sort as:
    	// SOURCE, TARGET, JOURNAL
    	// We want SOURCE volumes to be processed first below to populate the 
    	// productionCopies in order.
    	VolumeDescriptor.sortByType(volumeDescriptors);
    	    	    	
    	// Next create all of the request objects we need    	
    	for (VolumeDescriptor volumeDescriptor : volumeDescriptors) {
    	    Volume volume = null;
    	    if (volumeMap.containsKey(volumeDescriptor.getVolumeURI())) {
    	        volume = volumeMap.get(volumeDescriptor.getVolumeURI());
    	    } else {
    	        volume = _dbClient.queryObject(Volume.class, volumeDescriptor.getVolumeURI());
                volumeMap.put(volume.getId(), volume);
    	    }			
    	    
    	    boolean isMetroPoint = _rpHelper.isMetroPointVolume(volume);
			boolean isRPSource = _rpHelper.isRPSource(volumeDescriptor);
			boolean isRPTarget = _rpHelper.isRPTarget(volumeDescriptor);
			boolean extraParamsGathered = false;
			
			// Set up the source and target volumes in their respective replication sets
			if (isRPSource || isRPTarget) {					   
			    // Gather the extra params we need (once is sufficient)
			    if (isRPSource && !extraParamsGathered) {
    			    project = _dbClient.queryObject(Project.class, volume.getProject());
                    cg = _dbClient.queryObject(BlockConsistencyGroup.class, volumeDescriptor.getCapabilitiesValues().getBlockConsistencyGroup());
                    cgName = cg.getNameOnStorageSystem(rpSystem.getId());
                    if (cgName == null) {
                        cgName = CG_NAME_PREFIX + cg.getLabel();
                    }
                    copyMode = volumeDescriptor.getCapabilitiesValues().getRpCopyMode();
                    rpoType  = volumeDescriptor.getCapabilitiesValues().getRpRpoType();
                    rpoValue = volumeDescriptor.getCapabilitiesValues().getRpRpoValue();
                    // Flag so we only grab this information once
                    extraParamsGathered = true;
			    }
			    
				if (isMetroPoint && isRPSource) {
					// we need to handle metropoint request a bit differently.
					// since the same metro volume will be part of 2 (production) copies in the replication set,
					// we need to fetch the correct internal site names and other site related parameters from the backing volume.
				    StringSet backingVolumes = volume.getAssociatedVolumes();
					for (String backingVolumeStr : backingVolumes) {
						Volume backingVolume = _dbClient.queryObject(Volume.class, URI.create(backingVolumeStr));
						CreateVolumeParams volumeParams = populateVolumeParams(volume.getId(), 
                                                			   					volume.getStorageController(), 
                                                			   					backingVolume.getVirtualArray(),
                                                			   					backingVolume.getInternalSiteName(), 
                                                			   					true, 
                                                			   					backingVolume.getRpCopyName(),
                                                			   					volume.getWWN());								
						_log.info(String.format("Creating RSet Param for MetroPoint RP PROD - VOLUME: [%s] Name: [%s]", 
						                            backingVolume.getLabel(), backingVolume.getRSetName()));
						populateRsetsMap(rsetParamsMap, volumeParams, volume);   
						productionCopies.add(backingVolume.getRpCopyName());
					}
				} else {				    
					CreateVolumeParams volumeParams = populateVolumeParams(volume.getId(), 
                                                						   volume.getStorageController(), volume.getVirtualArray(),
                                                		   				   volume.getInternalSiteName(), 
                                                		   				   isRPSource, 
                                                		   				   volume.getRpCopyName(), 
                                                		   				   volume.getWWN());
					String type = isRPSource ? "PROD" : "TARGET";
					_log.info(String.format("Creating RSet Param for RP %s - VOLUME: [%s] Name: [%s]", 
					                            type, volume.getLabel(), volume.getRSetName()));									
					populateRsetsMap(rsetParamsMap, volumeParams, volume);
					if (isRPSource) {
					    productionCopies.add(volume.getRpCopyName());
					}
				}
   			}
			
			// Set up the journal volumes in the copy objects
			if (volumeDescriptor.getType().equals(VolumeDescriptor.Type.RP_JOURNAL) 
			        || volumeDescriptor.getType().equals(VolumeDescriptor.Type.RP_VPLEX_VIRT_JOURNAL)) {				
       			CreateVolumeParams volumeParams = populateVolumeParams(volume.getId(), 
                                                       					volume.getStorageController(), 
                                                       					volume.getVirtualArray(),
                                                       					volume.getInternalSiteName(), 
                                                       					_rpHelper.isProductionJournal(productionCopies, volume),
                                                       					volume.getRpCopyName(),
                                                       					volume.getWWN());
       			String key = volume.getRpCopyName();
       			_log.info(String.format("Creating Copy Param for RP JOURNAL: VOLUME - [%s] Name: [%s]", volume.getLabel(), key));
       			if (copyParamsMap.containsKey(key)) {       			
       				copyParamsMap.get(key).getJournals().add(volumeParams);
       			} else {
       				CreateCopyParams copyParams = new CreateCopyParams();
       				copyParams.setName(key);
       				copyParams.setJournals(new ArrayList<CreateVolumeParams>());
       				copyParams.getJournals().add(volumeParams);
       				copyParamsMap.put(key, copyParams);
       			}
       		}
    	}    	
    	    	
    	// Set up the CG Request
    	CGRequestParams cgParams = new CGRequestParams();
        cgParams.setCopies(new ArrayList<CreateCopyParams>());
        cgParams.getCopies().addAll(copyParamsMap.values());
        cgParams.setRsets(new ArrayList<CreateRSetParams>());
        cgParams.getRsets().addAll(rsetParamsMap.values());
        cgParams.setCgName(cgName);
        cgParams.setCgUri(cg.getId());
        cgParams.setProject(project.getId());
        cgParams.setTenant(project.getTenantOrg().getURI());
        cgParams.cgPolicy = new CGRequestParams.CGPolicyParams();
        cgParams.cgPolicy.copyMode = copyMode;
        cgParams.cgPolicy.rpoType = rpoType;
        cgParams.cgPolicy.rpoValue = rpoValue;
        _log.info(String.format("CG Request param complete:\n %s", cgParams));
        return cgParams;
    }

    /**
     * Adds the volumes to the replication sets map. 
     * 
     * @param rsetParamsMap the replication sets map.
     * @param volumeParams the volume params.
     * @param volume the volume from which to pull the replication set name.
     */
	private void populateRsetsMap(Map<String, CreateRSetParams> rsetParamsMap, CreateVolumeParams volumeParams,
			Volume volume) {
		String key = volume.getRSetName();
		if (rsetParamsMap.containsKey(key)) {
			rsetParamsMap.get(key).getVolumes().add(volumeParams);
		} else {
			CreateRSetParams rsetParams = new CreateRSetParams();
			rsetParams.setName(key);
			rsetParams.setVolumes(new ArrayList<CreateVolumeParams>());
			rsetParams.getVolumes().add(volumeParams);
			rsetParamsMap.put(key, rsetParams);
		}
	}
    
    /**
     * Assemble the CreateVolumeParams object with the input arguments.
     * Written to keep the prepare code tidy.
     *
     * @param volumeId Volume URI
     * @param storageSystemId Storage system for this Volume
     * @param neighborhoodId Neighborhood for this volume
     * @param internalSiteName internal site name
     * @param production Whether or not this volume is a production (source) volume at the time of the request
     * @param wwn volume wwn
     * @return volume parameter for RP
     */
    private CreateVolumeParams populateVolumeParams(URI volumeId, URI storageSystemId, URI neighborhoodId, 
    		String internalSiteName, boolean production, String rpCopyName, String wwn)
    {
    	CreateVolumeParams volumeParams = new CreateVolumeParams();
        volumeParams.setVirtualArray(neighborhoodId);
        volumeParams.setProduction(production);
        volumeParams.setInternalSiteName(internalSiteName);
        volumeParams.setStorageSystem(storageSystemId);
        volumeParams.setVolumeURI(volumeId);
        volumeParams.setRpCopyName(rpCopyName);
        volumeParams.setWwn(wwn);       
        return volumeParams;
    }
       
    /**
     * @param workflow
     * @param volumeDescriptorsTypeFilter 
     * @param waitFor
     * @param volumeDescriptors
     * @param params
     * @param rpSystem
     * @param taskId
     * @throws RecoverPointException
     * @throws ControllerException
     * @throws DeviceControllerException
     */
    private void addExportVolumesSteps(Workflow workflow, List<VolumeDescriptor> volumeDescriptors, 
    		String waitFor, ProtectionSystem rpSystem, String taskId)
            throws InternalException {
    	
    	// This step creates a sub-workflow to do the orchestration. The rollback for this step calls a 
    	// workflow facility WorkflowService.rollbackChildWorkflow, which will roll back the entire
    	// orchestration sub-workflow. The stepId of the orchestration create step must be passed to
    	// the rollback step so that rollbackChildWorkflow can locate the correct child workflow.
        String stepId = workflow.createStepId();
        Workflow.Method exportOrchestrationExecuteMethod = new Workflow.Method(METHOD_EXPORT_ORCHESTRATE_STEP,
                volumeDescriptors,                
                rpSystem.getId());
                
        Workflow.Method exportOrchestrationExecutionRollbackMethod = 
        		new Workflow.Method(METHOD_EXPORT_ORCHESTRATE_ROLLBACK_STEP, workflow.getWorkflowURI(), stepId);

        workflow.createStep(STEP_EXPORT_ORCHESTRATION, "Create export group orchestration subtask for RP CG",
                waitFor, rpSystem.getId(), rpSystem.getSystemType(), false, this.getClass(),
                exportOrchestrationExecuteMethod, exportOrchestrationExecutionRollbackMethod, stepId);
    }    	
    	
    /**
     * Workflow step method for rolling back the ExportOrchestration sub-workflow steps.
     *
     * @param parentWorkflow -- the URI of the parent Workflow, which is used to locate the sub-workflow
     * @param exportOrchestrationStepId -- the Step id of the of the step that creates the Export Orchestration sub-workflow.
     * @param token the task -- the step id for the rollback step
     * @return
     * @throws WorkflowException 
     */
    public boolean exportOrchestrationRollbackSteps(URI parentWorkflow, String exportOrchestrationStepId, String token) throws WorkflowException {
    	// The workflow service now provides a rollback facility for a child workflow. It rolls back every step in an already
    	// (successfully) completed child workflow. The child workflow is located by the parentWorkflow URI and exportOrchestrationStepId.
    	_workflowService.rollbackChildWorkflow(parentWorkflow, exportOrchestrationStepId, token);
        return true;
    }

    public boolean exportOrchestrationSteps(List<VolumeDescriptor> volumeDescriptors, URI rpSystemId, String taskId)
    		throws InternalException {
    	List<URI> volUris = VolumeDescriptor.getVolumeURIs(volumeDescriptors);
    	RPCGExportOrchestrationCompleter completer = new RPCGExportOrchestrationCompleter(volUris, taskId);
    	Workflow workflow = null;
    	boolean lockException = false;
    	try {
            // Generate the Workflow.
            workflow = _workflowService.getNewWorkflow(this,
                    EXPORT_ORCHESTRATOR_WF_NAME, true, taskId);
            
    		String waitFor = null;    // the wait for key returned by previous call
    		
    		ProtectionSystem rpSystem = _dbClient.queryObject(ProtectionSystem.class, rpSystemId);
    		
    		 // Get the CG Params based on the volume descriptors
            CGRequestParams params = this.getCGRequestParams(volumeDescriptors, rpSystem);
            updateCGParams(params);
            
    		_log.info("Start adding RP Export Volumes steps....");

    		// Get the RP Exports from the CGRequestParams object
    		Collection<RPExport> rpExports = generateStorageSystemExportMaps(params, volumeDescriptors);    		

    		// For each RP Export, create a workflow to either add the volumes to an existing export group
    		// or create a new one.
    		for (RPExport rpExport : rpExports) {            
    			URI storageSystemURI = rpExport.getStorageSystem();
    			String internalSiteName = rpExport.getRpSite();
    			URI varrayURI = rpExport.getVarray();
    			List<URI> volumes = rpExport.getVolumes();                                             

    			List<URI> initiatorSet = new ArrayList<URI>();

    			String rpSiteName = (rpSystem.getRpSiteNames() != null) ? rpSystem.getRpSiteNames().get(internalSiteName) : internalSiteName;

                StorageSystem storageSystem =
    					_dbClient.queryObject(StorageSystem.class, storageSystemURI);
                
                VirtualArray varray =
                        _dbClient.queryObject(VirtualArray.class, varrayURI);
                
                _log.info("--------------------");
                _log.info(String.format("RP Export: StorageSystem = [%s] RPSite = [%s] VirtualArray = [%s]", storageSystem.getLabel(), rpSiteName, varray.getLabel()));
                
                // Setup the export group - we may or may not need to create it, but we need to have everything ready in case we do 
                ExportGroup exportGroup = new ExportGroup();     
                exportGroup.addInternalFlags(Flag.INTERNAL_OBJECT, Flag.SUPPORTS_FORCE, Flag.RECOVERPOINT);
                exportGroup.setLabel(params.getCgName());
                exportGroup.setId(URIUtil.createId(ExportGroup.class));
                exportGroup.setProject(new NamedURI(params.getProject(), exportGroup.getLabel()));
                exportGroup.setVirtualArray(varrayURI);
                exportGroup.setTenant(new NamedURI(params.getTenant(), exportGroup.getLabel()));
                String exportGroupGeneratedName = rpSystem.getNativeGuid() + "_" + storageSystem.getLabel() + "_" + rpSiteName + "_" + varray.getLabel();
                // Remove all non alpha-numeric characters, excluding "_".  
                exportGroupGeneratedName = exportGroupGeneratedName.replaceAll("[^A-Za-z0-9_]", "");           
                exportGroup.setGeneratedName(exportGroupGeneratedName);
                // Set the option to Zone all initiators. If we dont do this, only available Storage Ports will be zoned to initiators. This means that if there are 4 available storage 
                // ports on the storage array, then only 4 RP initiators will be zoned to those ports. No storage ports will be re-used for other initiators. 
                // This might be OK for storage arrays that are not of type VPLEX, but VPLEX will have an issue with this preventing the exporting of VPLEX volumes to RPAs. 
                exportGroup.setZoneAllInitiators(true);
            
                // Get the initiators of the RP Cluster (all of the RPAs on one side of a configuration)
                Map<String, String> wwns = RPHelper.getRecoverPointClient(rpSystem).getInitiatorWWNs(internalSiteName);

				// Convert to initiator object
				List<Initiator> initiators = new ArrayList<Initiator>();
				for (String wwn : wwns.keySet()) {
					Initiator initiator = new Initiator();
					initiator.addInternalFlags(Flag.RECOVERPOINT);
					initiator.setHostName(rpSiteName);
					initiator.setInitiatorPort(wwn);
					initiator.setInitiatorNode(wwns.get(wwn));
					initiator.setProtocol("FC");
					initiator.setIsManualCreation(false);                    
					initiator = getInitiator(initiator);
					initiators.add(initiator);
				}

				if (wwns == null || wwns.isEmpty()) {
					throw DeviceControllerExceptions.recoverpoint.noInitiatorsFoundOnRPAs();
				}

				// We need to find and distill only those RP initiators that correspond to the network of the storage system and 
				// that network has front end port from the storage system. 
				// In certain lab environments, its quite possible that there are 2 networks one for the storage system FE ports and one for the BE ports.
				// In such configs, RP initiators will be spread across those 2 networks. RP controller does not care about storage system back-end ports, so 
				// we will ignore those initiators that are connected to a network that has only storage system back end port connectivity.
				Map<URI, Set<Initiator>> rpNetworkToInitiatorsMap = new HashMap<URI, Set<Initiator>>();
				if (initiators != null) {
					for (Initiator initiator: initiators) {
						URI rpInitiatorNetworkURI = getInitiatorNetwork(exportGroup, initiator);
						if (rpInitiatorNetworkURI != null) {
							if (rpNetworkToInitiatorsMap.get(rpInitiatorNetworkURI) == null) {
								rpNetworkToInitiatorsMap.put(rpInitiatorNetworkURI, new HashSet<Initiator>());
							}
							rpNetworkToInitiatorsMap.get(rpInitiatorNetworkURI).add(initiator);                                           
							_log.info("RP Initiator [" + initiator.getInitiatorPort() + "] found on network: [" + rpInitiatorNetworkURI.toASCIIString() + "]");
						} else {
							_log.warn("RP Initiator [" + initiator.getInitiatorPort() + "] was not found in any network. Excluding from automated exports");
						}
					}
				}

				// Compute numPaths. This is how its done:
				// We know the RP site and the Network/TransportZone it is on.
				// Determine all the storage ports for the storage array for all the networks they are on. 
				// Next, if we find the network for the RP site in the above list, return all the storage ports corresponding to that.
				// For RP we will try and use as many Storage ports as possible.                
				Map<URI, List<StoragePort>> initiatorPortMap = getInitiatorPortsForArray(
						rpNetworkToInitiatorsMap, storageSystemURI, varrayURI);     

				for (URI networkURI : initiatorPortMap.keySet()) {                
					for (StoragePort storagePort : initiatorPortMap.get(networkURI)) {
						_log.info("Network = [" + networkURI.toString() + "] PORT : [" +  storagePort.getLabel() + "]");
					}
				}

				int numPaths = computeNumPaths(initiatorPortMap, varrayURI, storageSystem);
				_log.info("Total paths = " + numPaths);								

				// Stems from above comment where we distill the RP network and the initiators in that network. 
				List<Initiator> initiatorList = new ArrayList<Initiator>();
				for (URI rpNetworkURI : rpNetworkToInitiatorsMap.keySet()) {
					if (initiatorPortMap.containsKey(rpNetworkURI)) {
						initiatorList.addAll(rpNetworkToInitiatorsMap.get(rpNetworkURI));
					}
				}							         

				for (Initiator initiator: initiatorList) {
                    initiatorSet.add(initiator.getId());                   
                }  
				
            	List<String> lockKeys = ControllerLockingUtil
            			.getHostStorageLockKeys(_dbClient, 
            					ExportGroupType.Host,
            					initiatorSet, storageSystemURI);
            	boolean acquiredLocks = _exportWfUtils.getWorkflowService().acquireWorkflowStepLocks(
            	        taskId, lockKeys, LockTimeoutValue.get(LockType.RP_EXPORT));
            	if (!acquiredLocks) {
            	    lockException = true;
            		throw DeviceControllerException.exceptions.failedToAcquireLock(lockKeys.toString(), 
            				"ExportOrchestrationSteps: " + exportGroup.getLabel());
            	}		
            	
            	// See if the export group already exists
                ExportGroup exportGroupInDB = exportGroupExistsInDB(exportGroup);
                boolean addExportGroupToDB = false;          
                if (exportGroupInDB != null) {
                    exportGroup = exportGroupInDB;
                    // If the export already exists, check to see if any of the volumes have already been exported. No need to 
                    // re-export volumes.
                    List<URI> volumesToRemove = new ArrayList<URI>();
                    for (URI volumeURI : volumes) {
                        if (exportGroup.getVolumes() != null 
                                && !exportGroup.getVolumes().isEmpty()
                                && exportGroup.getVolumes().containsKey(volumeURI.toString())) {
                            _log.info(String.format("Volume [%s] already exported to export group [%s], " +
                                                        "it will be not be re-exported", volumeURI.toString(), exportGroup.getGeneratedName()));
                            volumesToRemove.add(volumeURI);
                        }
                    }
                    
                    // Remove volumes if they have already been exported
                    if (!volumesToRemove.isEmpty()) {
                        volumes.removeAll(volumesToRemove);
                    }
                    
                    // If there are no more volumes to export, skip this one and continue,
                    // nothing else needs to be done here.
                    if (volumes.isEmpty()) {
                        _log.info(String.format("No volumes needed to be exported to export group [%s], continue", exportGroup.getGeneratedName()));
                        continue;
                    }         
                } 
                else {
                    addExportGroupToDB = true;
                }
                               
    			// Add volumes to the export group
    			Map<URI, Integer> volumesToAdd = new HashMap<URI, Integer>();
    			for (URI volumeID : volumes) {
    				exportGroup.addVolume(volumeID, ExportGroup.LUN_UNASSIGNED);
    				volumesToAdd.put(volumeID, ExportGroup.LUN_UNASSIGNED);
    			}

    			// Persist the export group
    			if (addExportGroupToDB) {    		
    			    exportGroup.addInitiators(initiatorSet);
    			    exportGroup.setNumPaths(numPaths);
    			    _dbClient.createObject(exportGroup);
    			} else {
    			    _dbClient.persistObject(exportGroup);
    			}

            	// If the export group already exists, add the volumes to it, otherwise create a brand new
    			// export group.
    			StringBuilder buffer = new StringBuilder();
    			if (!addExportGroupToDB) {
    				buffer.append(String.format("Adding volumes to existing Export Group for Storage System [%s], RP Site [%s], Virtual Array [%s]\n", storageSystem.getLabel(), rpSiteName, varray.getLabel()));
    				buffer.append(String.format("Export Group name is : [%s]\n", exportGroup.getGeneratedName()));
    				buffer.append(String.format("Export Group will have these volumes added: [%s]\n", Joiner.on(',').join(volumes)));
    				_log.info(buffer.toString());

    				waitFor = _exportWfUtils.
    				generateExportGroupAddVolumes(workflow, STEP_EXPORT_GROUP,
    						waitFor, storageSystemURI,
    						exportGroup.getId(), volumesToAdd);

    				_log.info("Added Export Group add volumes step in workflow");
    			}
    			else {
    				buffer.append(String.format("Creating new Export Group for Storage System [%s], RP Site [%s], Virtual Array [%s]\n", storageSystem.getLabel(), rpSiteName, varray.getLabel()));
    				buffer.append(String.format("Export Group name is: [%s]\n", exportGroup.getGeneratedName()));
    				buffer.append(String.format("Export Group will have these initiators: [%s]\n", Joiner.on(',').join(initiatorSet)));
    				buffer.append(String.format("Export Group will have these volumes added: [%s]\n", Joiner.on(',').join(volumes)));
    				_log.info(buffer.toString());

    				String exportStep = workflow.createStepId();
    				initTaskStatus(exportGroup, exportStep, Operation.Status.pending, "create export");

    				waitFor = _exportWfUtils.
    				generateExportGroupCreateWorkflow(workflow,
    						STEP_EXPORT_GROUP, waitFor,
    						storageSystemURI, exportGroup.getId(),
    						volumesToAdd, initiatorSet);

    				_log.info("Added Export Group create step in workflow. New Export Group Id: " + exportGroup.getId());
    			}
    		}

    		String successMessage = "Export orchestration completed successfully";          
    		
			// Finish up and execute the plan.
    		// The Workflow will handle the TaskCompleter    		
    		Object[] callbackArgs = new Object[] { volUris };
    		workflow.executePlan(completer, successMessage, new WorkflowCallback(), callbackArgs, null, null);

    	} catch (Exception ex) {
    		_log.error("Could not create volumes: " + volUris, ex);
    		if (workflow != null) {
    		    _workflowService.releaseAllWorkflowLocks(workflow);
    		}
    		String opName = ResourceOperationTypeEnum.CREATE_BLOCK_VOLUME.getName();
    		ServiceError serviceError = null;
    		if (lockException) {
                serviceError = DeviceControllerException.errors.createVolumesAborted(volUris.toString(), ex);
    		} else {
        		serviceError = DeviceControllerException.errors.createVolumesFailed(
        				volUris.toString(), opName, ex);
    		}
    		completer.error(_dbClient, _locker, serviceError);
    		return false;
    	}

    	_log.info("End adding RP Export Volumes steps.");
    	return true;
    }
    
    @SuppressWarnings("serial")
    private static class WorkflowCallback implements Workflow.WorkflowCallbackHandler, Serializable {
        @SuppressWarnings("unchecked")
        @Override
        public void workflowComplete(Workflow workflow, Object[] args)
                throws WorkflowException {
            List<URI> volumes = (List<URI>) args[0];
            String msg = BlockDeviceController.getVolumesMsg(_dbClient, volumes);
            _log.info("Processed volumes:\n" + msg);
        }
    }
    
    /**
     * Recoverpoint specific workflow method for creating an Export Group
     * NOTE: Workflow.Method requires that opId is added as a param.
     * 
     * @param opId
     */
    public boolean createExportGroupStep(String opId) {
    	// This is currently a dummy workflow step. If there are any specific things
    	// that need to be added for RP Export Group create, they can be added here.
    	WorkflowStepCompleter.stepSucceded(opId);
    	return true;
    }

    /**
     * Recoverpoint specific rollback for creating an Export Group
     * NOTE: Workflow.Method requires that opId is added as a param.
     * 
     * @param exportGroupURI
     * @param opId
     * @throws ControllerException
     */
    public void createExportGroupRollbackStep(URI exportGroupURI, String opId) throws ControllerException {
    	try {
    		_log.info(String.format("rollbackCreateRPExportGroup start - Export Group: [%s]", exportGroupURI)); 
    		
    		WorkflowStepCompleter.stepExecuting(opId);   		
    		
    		// If there was a rollback triggered, we need to cleanup the Export Group we created.
    		ExportGroup exportGroup =  _dbClient.queryObject(ExportGroup.class, exportGroupURI);
        	exportGroup.setInactive(true);
        	_dbClient.persistObject(exportGroup);
        	
        	_log.info(String.format("Rollback complete for Export Group: [%s]", exportGroupURI)); 
        	
        	WorkflowStepCompleter.stepSucceded(opId);
        	
        	_log.info(String.format("rollbackCreateRPExportGroup end - Export Group: [%s]", exportGroupURI));
    	} catch (InternalException e) {
    		_log.error(String.format("rollbackCreateRPExportGroup Failed - Export Group: [%s]", exportGroupURI));
    		WorkflowStepCompleter.stepFailed(opId, e);
    	} catch (Exception e) {
    		_log.error(String.format("rollbackCreateRPExportGroup Failed - Export Group: [%s]", exportGroupURI));
		    WorkflowStepCompleter.stepFailed(opId, DeviceControllerException.errors.jobFailed(e));
    	}
    }

    /**
     * Method that adds the step to the workflow that creates the CG.
     *
     * @param workflow
     * @param recommendation
     * @param rpSystem
     * @param protectionSet
     * @throws InternalException
     */
	private void addCreateCGStep(Workflow workflow, List<VolumeDescriptor> volumeDescriptors, CGRequestParams cgParams, ProtectionSystem rpSystem,
			String taskId) throws InternalException {
        String stepId = workflow.createStepId();
        Workflow.Method cgCreationExecuteMethod = new Workflow.Method(METHOD_CG_CREATE_STEP,
                rpSystem.getId(),
                volumeDescriptors);
        Workflow.Method cgCreationExecutionRollbackMethod = new Workflow.Method(METHOD_CG_CREATE_ROLLBACK_STEP,
                rpSystem.getId());

        workflow.createStep(STEP_CG_CREATION, "Create consistency group subtask for RP CG: " + cgParams.getCgName(),
                STEP_EXPORT_ORCHESTRATION, rpSystem.getId(), rpSystem.getSystemType(), this.getClass(),
                cgCreationExecuteMethod, cgCreationExecutionRollbackMethod, stepId);
    }

    /**
     * Workflow step method for creating/updating a consistency group.
     *
     * @param rpSystemId RP system Id
     * @param recommendation parameters needed to create the CG
     * @param token the task
     * @return
     * @throws InternalException 
     */
    public boolean cgCreateStep(URI rpSystemId, List<VolumeDescriptor> volumeDescriptors, String token) throws InternalException {
        RecoverPointClient rp;
        CGRequestParams cgParams = null;
        boolean metropoint = false;
        boolean lockException = false;
        try {
        	
        	// Get only the RP volumes from the descriptors.
	        List<VolumeDescriptor> sourceVolumeDescriptors = VolumeDescriptor.filterByType(volumeDescriptors, 
	                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.RP_SOURCE, 		        											        		                             
	        		                              VolumeDescriptor.Type.RP_EXISTING_SOURCE,
	        		                              VolumeDescriptor.Type.RP_VPLEX_VIRT_SOURCE }, 
	                new VolumeDescriptor.Type[] { });
        	
        	WorkflowStepCompleter.stepExecuting(token);
            ProtectionSystem rpSystem = _dbClient.queryObject(ProtectionSystem.class, rpSystemId);
            URI cgId = volumeDescriptors.iterator().next().getCapabilitiesValues().getBlockConsistencyGroup();
            BlockConsistencyGroup cg = _dbClient.queryObject(BlockConsistencyGroup.class, cgId);
	        
        	for (VolumeDescriptor sourceVolumedescriptor : sourceVolumeDescriptors) {
        		Volume sourceVolume = _dbClient.queryObject(Volume.class, sourceVolumedescriptor.getVolumeURI());
        		metropoint = _rpHelper.isMetroPointVolume(sourceVolume);
        	}
        	        	
        	//Build the CG Request params
        	cgParams = getCGRequestParams(volumeDescriptors, rpSystem);
            updateCGParams(cgParams);
            
            // Validate the source/target volumes before creating a CG.
            validateCGVolumes(volumeDescriptors);
         
            rp = RPHelper.getRecoverPointClient(rpSystem);
            
            // scan the rp sites for volume visibility
            rp.waitForVolumesToBeVisible(cgParams);
            
            // lock around create and delete operations on the same CG
            List<String> lockKeys = new ArrayList<String>();
            lockKeys.add(ControllerLockingUtil.getConsistencyGroupStorageKey(cgId, rpSystem.getId()));
            boolean lockAcquired = _workflowService.acquireWorkflowStepLocks(token, lockKeys, LockTimeoutValue.get(LockType.RP_CG));
            if (!lockAcquired) {
                lockException = true;
                throw DeviceControllerException.exceptions.failedToAcquireLock(lockKeys.toString(), 
                        String.format("Create or add volumes to RP consistency group %s; id: %s", cg.getLabel(), cgId.toString()));
            }

            RecoverPointCGResponse response = null;
            // The CG already exists if it contains volumes and is of type RP
            _log.info("Submitting RP Request: " + cgParams);
            if (cg.nameExistsForStorageSystem(rpSystem.getId(), cgParams.getCgName()) && rp.doesCgExist(cgParams.getCgName())) {
                // cg exists in both the ViPR db and on the RP system
                response = rp.addReplicationSetsToCG(cgParams, metropoint);
            } else {
                response = rp.createCG(cgParams, metropoint);

                // "Turn-on" the consistency group
                cg = _dbClient.queryObject(BlockConsistencyGroup.class, cgParams.getCgUri());
                cg.addSystemConsistencyGroup(rpSystemId.toString(), cgParams.getCgName());
                cg.addConsistencyGroupTypes(Types.RP.name());
                _dbClient.persistObject(cg);
            }
             
            setVolumeConsistencyGroup(volumeDescriptors, cgParams.getCgUri());
            
            // If this was a vpool Update, now is a good time to update the vpool and Volume information
            if (VolumeDescriptor.getVirtualPoolChangeVolume(volumeDescriptors) != null) {
                Volume volume = _dbClient.queryObject(Volume.class, VolumeDescriptor.getVirtualPoolChangeVolume(volumeDescriptors));
                URI newVpoolURI = getVirtualPoolChangeVirtualPool(volumeDescriptors);
                volume.setVirtualPool(newVpoolURI);
                volume.setPersonality(Volume.PersonalityTypes.SOURCE.toString());
                volume.setAccessState(Volume.VolumeAccessState.READWRITE.name());
                volume.setLinkStatus(Volume.LinkStatus.IN_SYNC.name());
                volume.setProtectionController(rpSystemId);
                _dbClient.persistObject(volume);
                
                // We might need to update the vpools of the backing volumes if this is an RP+VPLEX
                // or MetroPoint change vpool.
                updateVPlexBackingVolumeVpools(volume, newVpoolURI);                             

                // Record Audit operation. (virtualpool change only)
                AuditBlockUtil.auditBlock(_dbClient, OperationTypeEnum.CHANGE_VOLUME_VPOOL,
                        true, AuditLogManager.AUDITOP_END, token);
            }
                       
            // Create the ProtectionSet to contain the CG UID (which is truly unique to the protection system)
            if (response.getCgId() != null) {
            	List<ProtectionSet> protectionSets = CustomQueryUtility.queryActiveResourcesByConstraint(_dbClient, ProtectionSet.class, 
            			AlternateIdConstraint.Factory.getConstraint(ProtectionSet.class,
            			"protectionId",
            			response.getCgId().toString()));
            	ProtectionSet protectionSet = null;
            	
            	if (protectionSets.isEmpty()) {
            		// A protection set corresponding to the CG does not exist so we need to create one
            		protectionSet = createProtectionSet(rpSystem, cgParams);
            		protectionSet.setProtectionId(response.getCgId().toString());            		
            	} else {
            		// Update the existing protection set.  We will only have 1 protection set
            		// get the first one.
            		protectionSet = protectionSets.get(0);
            		protectionSet = updateProtectionSet(protectionSet, cgParams);
            	}
                _dbClient.persistObject(protectionSet);
            }
         
            // Set the CG last created time to now.
            rpSystem.setCgLastCreatedTime(Calendar.getInstance());
            _dbClient.persistObject(rpSystem);
            
            // Update the workflow state.
            WorkflowStepCompleter.stepSucceded(token);
            
            // collect and update the protection system statistics to account for
            // the newly created CG.
            _log.info("Collecting RP statistics post CG create.");
            collectRPStatistics(rpSystem);
		} catch (Exception e) {
		    if (lockException) {
		        List<URI> volUris = VolumeDescriptor.getVolumeURIs(volumeDescriptors);
		        ServiceError serviceError = DeviceControllerException.errors.createVolumesAborted(volUris.toString(), e);
                doFailCgCreateStep(volumeDescriptors, cgParams, rpSystemId, token);
                stepFailed(token, serviceError, "cgCreateStep");
		    } else {
		        doFailCgCreateStep(volumeDescriptors, cgParams, rpSystemId, token);
		        stepFailed(token, e, "cgCreateStep");
		    }
            return false;
        }
        return true;
    }

    /**
     * Sets the volume consistency group
     * @param volumeDescriptors
     * @param cgURI
     */
    private void setVolumeConsistencyGroup(List<VolumeDescriptor> volumeDescriptors, URI cgURI) {
    	for (VolumeDescriptor volumeDescriptor : volumeDescriptors) {
    		Volume volume = _dbClient.queryObject(Volume.class, volumeDescriptor.getVolumeURI());
    		volume.setConsistencyGroup(cgURI);
    		_dbClient.persistObject(volume);
    	}
    }
    
    /**
     * Validates the source and target volumes to ensure the provisioned
     * sizes are all the same.  
     * 
     * @param volumeDescriptors the volumes to validate
     */
    private void validateCGVolumes(List<VolumeDescriptor> volumeDescriptors) {
        // Validate that the source and target volumes are the same size.  If they are not
        // CG creation or failover will fail.
        VolumeDescriptor sourceVolumeDescriptor = null;
        List<VolumeDescriptor> targets = new ArrayList<VolumeDescriptor>();
        for (VolumeDescriptor volumeDescriptor : volumeDescriptors) {
        	if (volumeDescriptor.getType().equals(VolumeDescriptor.Type.RP_SOURCE) 
        	        || volumeDescriptor.getType().equals(VolumeDescriptor.Type.RP_EXISTING_SOURCE) 
        			|| volumeDescriptor.getType().equals(VolumeDescriptor.Type.RP_VPLEX_VIRT_SOURCE)) {
        		sourceVolumeDescriptor = volumeDescriptor;
        	} else if (volumeDescriptor.getType().equals(VolumeDescriptor.Type.RP_TARGET) 
        	            || volumeDescriptor.getType().equals(VolumeDescriptor.Type.RP_VPLEX_VIRT_TARGET)) {
        		targets.add(volumeDescriptor);
        	}
        }
        
        Volume sourceVolume = _dbClient.queryObject(Volume.class, sourceVolumeDescriptor.getVolumeURI());
        Volume targetVolume = null;
        StorageSystem sourceStorageSystem = 
        		_dbClient.queryObject(StorageSystem.class, sourceVolume.getStorageController());
        StorageSystem targetStorageSystem = null;
        
        for (VolumeDescriptor targetVolumeDescriptor: targets) {
        	
        	targetVolume = _dbClient.queryObject(Volume.class, targetVolumeDescriptor.getVolumeURI());
        	targetStorageSystem = 
            		_dbClient.queryObject(StorageSystem.class, targetVolume.getStorageController());
        	
        	// target must be equal to or larger than the source
        	if (Long.compare(targetVolume.getProvisionedCapacity(), sourceVolume.getProvisionedCapacity()) < 0) {
        		throw DeviceControllerExceptions.recoverpoint.cgCannotBeCreatedInvalidVolumeSizes(
        				sourceStorageSystem.getSystemType(),
        				String.valueOf(sourceVolume.getProvisionedCapacity()),
        				targetStorageSystem.getSystemType(),
        				String.valueOf(targetVolume.getProvisionedCapacity()));
        	}
        }
    }          	

	/**
	 * Helper method to retrieve the vpool change vpool hiding in the volume descriptors
	 * 
	 * @param volumeDescriptors list of volumes
	 * @return URI of the vpool change vpool
	 */
	private URI getVirtualPoolChangeVirtualPool(List<VolumeDescriptor> volumeDescriptors) {
		if (volumeDescriptors != null) {
			for (VolumeDescriptor volumeDescriptor : volumeDescriptors) {
				if (volumeDescriptor.getParameters() != null) {
					if ((URI)volumeDescriptor.getParameters().get(VolumeDescriptor.PARAM_VPOOL_CHANGE_VPOOL_ID) != null) {
						return (URI)volumeDescriptor.getParameters().get(VolumeDescriptor.PARAM_VPOOL_CHANGE_VPOOL_ID);
					}
				}
			}
		}
		return null;
	}

	/**
	 * process failure of creating a cg step.
	 * 
	 * @param volumeDescriptors volumes
	 * @param cgParams cg parameters
	 * @param protectionSetId protection set id
	 * @param token task ID for audit
	 * @param e exception
	 * @param lockException 
	 * @throws InternalException
	 */
	private void doFailCgCreateStep(
			List<VolumeDescriptor> volumeDescriptors, CGRequestParams cgParams, URI protectionSetId,
			String token) throws InternalException {
		// Record Audit operation. (vpool change only)
		if (VolumeDescriptor.getVirtualPoolChangeVolume(volumeDescriptors) != null) {
			AuditBlockUtil.auditBlock(_dbClient, OperationTypeEnum.CHANGE_VOLUME_VPOOL, true, AuditLogManager.AUDITOP_END, token);
		}
	}

    /**
     * Workflow step method for creating a consistency group.
     *
     * @param rpSystem RP system
     * @param params parameters needed to create the CG
     * @param token the task
     * @return
     * @throws WorkflowException 
     */
    public boolean cgCreateRollbackStep(URI rpSystemId, String token) throws WorkflowException {
        // nothing to do for now.
        WorkflowStepCompleter.stepSucceded(token);
        return true;
    }

    /**
     * Helper method that consolidates all of the volumes into storage systems to make the minimum amount of export calls.
     * @param volumeDescriptors 
     *
     * @param recommendation
     */
    private Collection<RPExport> generateStorageSystemExportMaps(CGRequestParams cgParams, List<VolumeDescriptor> volumeDescriptors) {
        _log.info("Generate the storage system exports");
        Map<String, RPExport> rpExportMap = new HashMap<String, RPExport>();
        
        // First, iterate through the journal volumes (via the copies)
        for (CreateCopyParams copy : cgParams.getCopies()) {
            _log.info("Copy: " + copy.getName());
            for (CreateVolumeParams journalVolume : copy.getJournals()) {
                // Retrieve the volume
                Volume volume = _dbClient.queryObject(Volume.class, journalVolume.getVolumeURI());
                
                URI storageSystem = journalVolume.getStorageSystem();
                String rpSiteName = volume.getInternalSiteName();
                URI varray = volume.getVirtualArray();                    
                String volumeLabel = volume.getLabel();
                URI volumeId = volume.getId();
                                                
                // Generate a unique key based on Storage System + Internal Site + Virtual Array
                String key = storageSystem.toString() + rpSiteName + varray.toString();
                
                // Try and get an existing rp export object from the map using the key
                RPExport rpExport = rpExportMap.get(key);
                
                // If it doesn't exist, create the entry and add it to the map with the key
                if (rpExport == null) {
                    rpExport = new RPExport(storageSystem, rpSiteName, varray);
                    rpExportMap.put(key, rpExport);
                }
                
                _log.info(String.format("Add Journal Volume: [%s] to export : [%s]", volumeLabel, rpExport));
                                    
                rpExport.getVolumes().add(volumeId);             
            }
        }
        
        // Second, iterate through source/target volumes (via the replication set). This will be slightly
        // different than the journals since we need to consider that we might have a MetroPoint source 
        // volume.
        for (CreateRSetParams rset : cgParams.getRsets()) {
            _log.info("Replication Set: " + rset.getName());    
            Set<CreateVolumeParams> uniqueVolumeParams = new HashSet<CreateVolumeParams>();
            uniqueVolumeParams.addAll(rset.getVolumes());
            for (CreateVolumeParams rsetVolume : uniqueVolumeParams) {            	           
            	// Retrieve the volume            	
                Volume volume = _dbClient.queryObject(Volume.class, rsetVolume.getVolumeURI());                 
                               
                // List of volumes, normally just one volume will be added to this list unless
                // we have a MetroPoint config. In which case we would have two (each leg of the VPLEX).
                Set<Volume> volumes = new HashSet<Volume>();
                
                // Check to see if this is a SOURCE volume
                if (volume.getPersonality().equals(PersonalityTypes.SOURCE.toString())) {                                                      
                    // Now check the vpool to ensure we're exporting to the source volume to then correct place or
                    // places in the case of MetroPoint, however, it could be a change vpool. In that case get the change
                    // vpool new vpool.
                    URI vpoolURI = null;
                    if (VolumeDescriptor.getVirtualPoolChangeVolume(volumeDescriptors) != null) {
                        vpoolURI = getVirtualPoolChangeVirtualPool(volumeDescriptors);
                    } else {
                        vpoolURI = volume.getVirtualPool();
                    }
                    
                    VirtualPool vpool = _dbClient.queryObject(VirtualPool.class, vpoolURI);
                    
                    // In an RP+VPLEX distributed setup, the user can choose to protect only the HA side, so we would export only to the
                    // HA StorageView on the VPLEX.
                    boolean exportToHASideOnly = VirtualPool.isRPVPlexProtectHASide(vpool);                                      
                                        
                    if (exportToHASideOnly || VirtualPool.vPoolSpecifiesMetroPoint(vpool)) {
                        _log.info("Export is for {}. Basing export(s) off backing VPLEX volumes for RP Source volume [{}].", 
                                (exportToHASideOnly ? "RP+VPLEX distributed HA side only" : "MetroPoint"),
                                volume.getLabel());
                        // If MetroPoint is enabled we need to create exports for each leg of the VPLEX.
                        // Get the associated volumes and add them to the list so we can create RPExports
                        // for each one.
                        for (String volumeId : volume.getAssociatedVolumes()) {
                            Volume vol = _dbClient.queryObject(Volume.class, URI.create(volumeId));
                            
                            // Check to see if we only want to export to the HA side of the RP+VPLEX setup
                            if (exportToHASideOnly) {
                                if (!vol.getVirtualArray().toString().equals(vpool.getHaVarrayConnectedToRp())) {
                                    continue;
                                }
                            }                                
                            volumes.add(vol);
                        }
                    } else {
                        // Not RP+VPLEX distributed or MetroPoint, add the volume and continue on.
                        volumes.add(volume);
                    }
                } else {
                    // Not a SOURCE volume, add the volume and continue on.
                    volumes.add(volume);
                }
                
                for (Volume vol : volumes) {
                    URI storageSystem = rsetVolume.getStorageSystem();
                    String rpSiteName = vol.getInternalSiteName();
                    URI varray = vol.getVirtualArray();    
                    // Intentionally want the label and ID of the parent volume, not the inner looping vol.
                    // This is because we could be trying to create exports for MetroPoint.
                    String volumeLabel = volume.getLabel();
                    URI volumeId = volume.getId();
                    
                    // Generate a unique key based on Storage System + Internal Site + Virtual Array
                    String key = storageSystem.toString() + rpSiteName + varray.toString();
                    
                    // Try and get an existing rp export object from the map using the key
                    RPExport rpExport = rpExportMap.get(key);
                    
                    // If it doesn't exist, create the entry and add it to the map with the key
                    if (rpExport == null) {
                        rpExport = new RPExport(storageSystem, rpSiteName, varray);
                        rpExportMap.put(key, rpExport);
                    }
                    
                    _log.info("Add Volume: " + volumeLabel + " to export: " + rpExport);
                                        
                    rpExport.getVolumes().add(volumeId);
                }
            }
        }
        
        return rpExportMap.values();
    }

    private boolean stepFailed(final String token, final String step)
            throws WorkflowException {
        WorkflowStepCompleter.stepFailed(token,
                DeviceControllerErrors.recoverpoint.stepFailed(step));
        return false;
    }

    private boolean stepFailed(final String token, final ServiceCoded e, final String step)
            throws WorkflowException {
        if (e != null) {
            _log.error(String.format("RecoverPoint %s step failed: Exception:", step), e);
        }
        
        WorkflowStepCompleter.stepFailed(token, e);
        return false;
    }

    private boolean stepFailed(final String token, final Exception e, final String step)
            throws WorkflowException {
        if (e != null) {
            _log.error(String.format("RecoverPoint %s step failed: Exception:", step), e);
        }
        
        if (e instanceof ServiceCoded) {
            WorkflowStepCompleter.stepFailed(token, (ServiceCoded) e);
            return false;
        }
        WorkflowStepCompleter.stepFailed(token,
                DeviceControllerErrors.recoverpoint.stepFailed(step));
        return false;
    }

    /**
     * The step that deletes the CG from the RecoverPoint appliance if all of the volumeIDs are in the request,
     * otherwise delete replication sets and associated journals.
     *
     * @param rpSystem protection system
     * @param volumeIDs volume IDs
     * @param token task ID
     * @return true if successful
     * @throws ControllerException 
     */
    public boolean cgDeleteStep(URI rpSystem, List<URI> volumeIDs, String token) throws ControllerException {
        WorkflowStepCompleter.stepExecuting(token);
        
        _log.info("cgDeleteStep is running");
        boolean lockException = false;
        try {
        	// Validate input arguments
        	if (rpSystem == null) {
        		_log.error("Protection system not sent into cgDeleteStep");
        		throw DeviceControllerExceptions.recoverpoint.cgDeleteStepInvalidParam("protection system URI");
        	}
        	
            ProtectionSystem system = _dbClient.queryObject(ProtectionSystem.class, rpSystem);
            if (system == null) {
        		_log.error("Protection system not in database"); 
        		throw DeviceControllerExceptions.recoverpoint.cgDeleteStepInvalidParam("protection system null");
            }
            
            if (system.getInactive()) {
            	_log.error("Protection system set to be deleted");
        		throw DeviceControllerExceptions.recoverpoint.cgDeleteStepInvalidParam("protection system deleted");
            }
            
            if (volumeIDs == null) {
            	_log.error("Volume IDs list is null");
        		throw DeviceControllerExceptions.recoverpoint.cgDeleteStepInvalidParam("volume IDs null");
            }

            if (volumeIDs.isEmpty()) {
            	_log.error("Volume IDs list is empty");
        		throw DeviceControllerExceptions.recoverpoint.cgDeleteStepInvalidParam("volume IDs empty");
            }
            
            List<Volume> volumes = _dbClient.queryObject(Volume.class, volumeIDs, true);
            if (volumes.isEmpty()) {
                _log.info("All volumes already deleted. Not performing RP CG operation");
                WorkflowStepCompleter.stepSucceded(token);
                return true;
            }
            
            BlockConsistencyGroup cg = _dbClient.queryObject(BlockConsistencyGroup.class, volumes.get(0).getConsistencyGroup());
            
            // lock around create and delete operations on the same CG
            List<String> lockKeys = new ArrayList<String>();
            Volume tempVol = _dbClient.queryObject(Volume.class, volumeIDs.iterator().next());
            lockKeys.add(ControllerLockingUtil.getConsistencyGroupStorageKey(tempVol.getConsistencyGroup(), system.getId()));
            boolean lockAcquired = _workflowService.acquireWorkflowStepLocks(token, lockKeys, LockTimeoutValue.get(LockType.RP_CG));
            if (!lockAcquired) {
                lockException = true;
                throw DeviceControllerException.exceptions.failedToAcquireLock(lockKeys.toString(), 
                        String.format("Delete or remove volumes from RP consistency group %s", cg.getNameOnStorageSystem(rpSystem)));
            }

            // Validate that all volumes belong to one protection set.  In the meantime, figure out the protection set for future use.
            ProtectionSet protectionSet = null;
            for (Volume volume : volumes) {
            	if (protectionSet == null) {
            		protectionSet = _dbClient.queryObject(ProtectionSet.class, volume.getProtectionSet());
            	} else if (!protectionSet.getId().equals(volume.getProtectionSet().getURI())) {
                	_log.error("Not all volumes belong to the same protection set.");
            		throw DeviceControllerExceptions.recoverpoint.cgDeleteStepInvalidParam("volumes from different protection sets");
            	}
            }

            // TODO: Check to make sure there are no other non-journal volumes in that copy
            // Check to see if there are any more volumes in this protection set.
            if (protectionSet == null || protectionSet.getInactive()) {
                _log.info("Protection Set was already deleted.  Not performing RP CG operation");
                WorkflowStepCompleter.stepSucceded(token);
                return true;
            }

        	RecoverPointClient rp = RPHelper.getRecoverPointClient(system);

        	// Validate that we found the protection info for each volume.
    		RecoverPointVolumeProtectionInfo volumeProtectionInfo = null;
        	for (Volume volume : volumes) {
        		try {
        			volumeProtectionInfo = rp.getProtectionInfoForVolume(volume.getWWN());
        			VirtualPool virtualPool = _dbClient.queryObject(VirtualPool.class, volume.getVirtualPool());
        			volumeProtectionInfo.setMetroPoint(VirtualPool.vPoolSpecifiesMetroPoint(virtualPool));
        		} catch (Exception e) {
        			_log.warn("Looks like the volume(s) we're trying to remove from the RP appliance are no longer associated with a RP CG, continuing delete process.");
        			WorkflowStepCompleter.stepSucceded(token);
        			return true;
        		}
        	}

        	if (RPHelper.containsAllRPSourceVolumes(_dbClient, protectionSet, volumeIDs)) {
        		// There are no more volumes in the protection set so delete the CG
        		rp.deleteCG(volumeProtectionInfo);
        		
        		// We want to reflect the CG being deleted in the BlockConsistencyGroup
        		if (volumeIDs != null && !volumeIDs.isEmpty()) {
        		    // Get the CG URI from the first volume
        		    Volume vol = _dbClient.queryObject(Volume.class, volumeIDs.get(0));
        		    
        		    if (vol.getConsistencyGroup() != null) {
            		    cg = _dbClient.queryObject(BlockConsistencyGroup.class, vol.getConsistencyGroup());
            		    cg.removeSystemConsistencyGroup(rpSystem.toString(), CG_NAME_PREFIX + cg.getLabel());
            		    _dbClient.persistObject(cg);
        		    }
        		    
                    if (protectionSet == null || protectionSet.getInactive() || protectionSet.getVolumes() == null || protectionSet.getVolumes().isEmpty()) {
                        _log.info("Cleanup unnecessary as protection set in ViPR is empty or has already been marked for deletion.");
                    } else {
                        _log.info("Removing all volume from protection set: " + protectionSet.getLabel());

                        // Remove all volumes in the ProtectionSet and mark for deletion
                        List<String> removeVolumeIDs = new ArrayList<String>(protectionSet.getVolumes());                        
                        cleanupProtectionSetVolumes(protectionSet, removeVolumeIDs, true);
                    }        		    
        		}
        		
        		setProtectionSetStatus(volumeProtectionInfo, ProtectionStatus.DISABLED.toString(), system);
        	} else {
        		for (Volume volume : volumes) {
        			// Delete the replication set if there are more volumes (other replication sets).
        			// If there are no other replications sets we will simply delete the CG instead.
        			volumeProtectionInfo = rp.getProtectionInfoForVolume(volume.getWWN());
        			rp.deleteReplicationSet(volumeProtectionInfo, volume.getWWN());
        			
        			// Now cleanup the ProtectionSet reference and the volumes reference to the CG.
                    
                    // Find all replication set volumes (except journals) that corresponding to the 
                    // current volume.  We need to remove these from the ProtectionSet.
                    List<String> removeVolumeIDs = new ArrayList<String>();
                    for (String protectionVolumeID : protectionSet.getVolumes()) {
                        URI uri = new URI(protectionVolumeID);
                        Volume protectionVolume = _dbClient.queryObject(Volume.class, uri);
                        if (protectionVolume != null && 
                                NullColumnValueGetter.isNotNullValue(protectionVolume.getRSetName()) &&                                  
                                protectionVolume.getRSetName().equals(volume.getRSetName())) {
                            removeVolumeIDs.add(protectionVolumeID);                                                                                
                        }
                    }

                    // Cleanup the ProtectionSet.
                    cleanupProtectionSetVolumes(protectionSet, removeVolumeIDs, false);
        		}

        		Volume journalVolume = null;        		
    			for (URI journalVolURI : _rpHelper.determineJournalsToRemove(protectionSet, volumeIDs)) {
    				journalVolume = _dbClient.queryObject(Volume.class, journalVolURI);
    				RecoverPointVolumeProtectionInfo journalVolumeProtectionInfo = 
    						rp.getProtectionInfoForVolume(journalVolume.getWWN());
    				rp.deleteJournalFromCopy(journalVolumeProtectionInfo, journalVolume.getWWN());
    				
                    List<String> journalsToRemove = new ArrayList<String>();
                    journalsToRemove.add(journalVolURI.toString());
                    
                    // Cleanup the ProtectionSet.
                    cleanupProtectionSetVolumes(protectionSet, journalsToRemove, false);
    			}
    		}     
    		WorkflowStepCompleter.stepSucceded(token);
    		_log.info("cgDeleteStep is complete");

    		// collect and update the protection system statistics to account for
    		// the CG that has been removed
    		_log.info("Collection RP statistics post CG delete.");

    		// Collect stats, even if we didn't delete the CG, because the volume count in the CG will go down.
    		collectRPStatistics(system);
		} catch (Exception e) {
            if (lockException) {
                ServiceError serviceError = DeviceControllerException.errors.deleteVolumesAborted(volumeIDs.toString(), e);
                return stepFailed(token, serviceError, "cgDeleteStep");
            } else {
                return stepFailed(token, e, "cgDeleteStep");
            }
        }
        return true;
    }

    /**
     * Cleans up the given ProtectionSet by removing volumes from it and marking for deletion if specified.
     * Also removes the volume's association on the BlockConsistencyGroup.
     * 
     * @param protectionSet the protection set from which to remove volumes.
     * @param volumeIDs the volume ids to remove from the protection set.
     * @param markProtectionSetForDeletion if true, marks the protection set for deletion.
     */
    private void cleanupProtectionSetVolumes(ProtectionSet protectionSet, List<String> volumeIDs, boolean markProtectionSetForDeletion) {
        _log.info("Removing the following volumes from protection set {}: {}", protectionSet.getLabel(), volumeIDs.toString());
        StringSet psetVolumes = protectionSet.getVolumes();
        psetVolumes.removeAll(volumeIDs);
        protectionSet.setVolumes(psetVolumes);
        
        if (markProtectionSetForDeletion) {
            // Mark the protection set for deletion
            protectionSet.setInactive(true);
        }
        
        _dbClient.persistObject(protectionSet);
    }    
    
    /**
     * The step that rolls back the delete of the CG from the RecoverPoint appliance.  It is a no-op.
     *
     * @param rpSystem protection system
     * @param volumeID volume ID
     * @param token task ID
     * @return true if successful
     * @throws WorkflowException 
     */
    public boolean cgDeleteRollbackStep(URI rpSystem, Set<URI> volumeIDs, String token) throws WorkflowException {
        WorkflowStepCompleter.stepExecuting(token);
        _log.info("cgDeleteStep rollback is a no-op");
        WorkflowStepCompleter.stepSucceded(token);
        return true;
    }

    /**
     * Add the steps that will remove the consistency groups (if this the last replication set in the CG),
     * otherwise it will remove the replication set associated with the volume.
     *
     * @param workflow workflow to add steps to
     * @param volumeID volume ID of the volume sent from the API
     * @throws InternalException
     */
    private String addDeleteCGStep(Workflow workflow, String waitFor, List<VolumeDescriptor> volumeDescriptors) throws InternalException {
    	String returnStep = waitFor;
    	
    	// Create a map of all of the protection sets this delete operation impacts.
    	Map<URI, Set<URI>> psetVolumeMap = new HashMap<URI, Set<URI>>();
    	for (VolumeDescriptor volumeDescriptor : volumeDescriptors) {    		
    		Volume volume = _dbClient.queryObject(Volume.class, volumeDescriptor.getVolumeURI());

    		if (volume.getProtectionSet() == null) {
    			// Don't try to delete the CG, there isn't one
    			return returnStep;
    		}
    		
    		if (psetVolumeMap.get(volume.getProtectionSet().getURI()) == null) {
    			psetVolumeMap.put(volume.getProtectionSet().getURI(), new HashSet<URI>());
    		}
    		psetVolumeMap.get(volume.getProtectionSet().getURI()).add(volume.getId());
    	}

    	// For each of the protection sets, create a series of steps to delete replication sets/cgs
    	for (URI psetId : psetVolumeMap.keySet()) {
    		ProtectionSet protectionSet = _dbClient.queryObject(ProtectionSet.class, psetId);
    		// All protection sets can be deleted at the same time, but only one step per protection set can be running
    		String psetWaitFor = waitFor; 

    		String stepId = workflow.createStepId();
    		ProtectionSystem rpSystem = _dbClient.queryObject(ProtectionSystem.class, protectionSet.getProtectionSystem());
    		List<URI> volumeList = new ArrayList<URI>();
    		volumeList.addAll(psetVolumeMap.get(psetId));
    		Workflow.Method cgRemovalExecuteMethod = new Workflow.Method(METHOD_DELETE_CG_STEP,
    				rpSystem.getId(),
    				volumeList);

    		// Make all of the steps in removing this CG (or replication sets from this CG) sequential.
    		psetWaitFor = workflow.createStep(STEP_DV_REMOVE_CG, "Remove consistency group subtask (if no more volumes) for RP CG: " + protectionSet.getLabel(),
    				psetWaitFor, rpSystem.getId(), rpSystem.getSystemType(), this.getClass(),
    				cgRemovalExecuteMethod, null, stepId);
    	}
    	return STEP_DV_REMOVE_CG;
    }

    /**
     * Add the steps that will remove the volumes from the export group
     * TODO: This could stand to be refactored to be simpler.
     *
     * @param workflow workflow object
     * @param waitFor step that these steps are dependent on
     * @param filteredSourceVolumeDescriptors volumes to act on
     * @return "waitFor" step that future steps should wait on
     * @throws InternalException
     */
    private String addExportRemoveVolumesSteps(Workflow workflow, String waitFor, List<VolumeDescriptor> filteredSourceVolumeDescriptors) throws InternalException {
        _log.info("Adding steps to remove volumes from export groups.");
    	String returnStep = waitFor;
    	Map<URI, ProtectionSystem> psetRPSystem = new HashMap<URI, ProtectionSystem>();
    	Set<URI> volumeURIs = new HashSet<URI>();
    	
    	// Find all related volumes from source volume descriptors passed in
    	for (VolumeDescriptor volumeDescriptor : filteredSourceVolumeDescriptors) {    		
    		Volume sourceVolume = _dbClient.queryObject(Volume.class, volumeDescriptor.getVolumeURI());
    		if (sourceVolume.getProtectionSet() != null) {        	
        			ProtectionSet pset = _dbClient.queryObject(ProtectionSet.class, sourceVolume.getProtectionSet());
        			if (pset != null && pset.getProtectionSystem() != null) {
        				psetRPSystem.put(sourceVolume.getProtectionSet().getURI(), _dbClient.queryObject(ProtectionSystem.class, pset.getProtectionSystem()));
        			}        			
        			// All checks worked out well, these are good volumes to unexport.
                    volumeURIs.addAll(_rpHelper.getVolumesToDelete(sourceVolume, VolumeDescriptor.getVolumeURIs(filteredSourceVolumeDescriptors)));        		
        		}  else {
    			// Don't try to delete the CG, there isn't one
    			_log.warn("No protection set information found associated with this volume.  Skipping unexport operation.");
    		}
    	}
    	        	
    	_log.info(String.format("Following volume(s) will be deleted :  [%s]", Joiner.on("--").join(volumeURIs)));
    	
		Map<URI, RPExport> rpExports = new HashMap<URI, RPExport>();		
		for (URI volumeURI : volumeURIs) {
    		Volume volume = _dbClient.queryObject(Volume.class, volumeURI);
    		if (volume == null) {
    		    _log.warn("Could not load volume with given URI: " + volumeURI);
    			continue;
    		}
    		
    		// Get the storage controller URI of the volume
    		URI storageURI = volume.getStorageController();
    		
    		// Get the vpool of the volume
    		VirtualPool virtualPool = _dbClient.queryObject(VirtualPool.class, volume.getVirtualPool());
    		
    		if (VirtualPool.isRPVPlexProtectHASide(virtualPool) && 
    				volume.getPersonality().equals((Volume.PersonalityTypes.SOURCE.toString()))) {    	    	
    		    _log.info(String.format("RP+VPLEX protect HA Source Volume [%s] to be removed from export group.", volume.getLabel()));
    		    // We are dealing with a RP+VPLEX distributed volume that has the HA as the protected side so we need to get 
    		    // the HA side export group only. 
                if (volume.getAssociatedVolumes() != null &&
                        volume.getAssociatedVolumes().size() == 2) { 
        		    for (String associatedVolURI : volume.getAssociatedVolumes()) {    		        
                        Volume associatedVolume = _dbClient.queryObject(Volume.class, URI.create(associatedVolURI));                        
                        if (associatedVolume.getVirtualArray().toString().equals(virtualPool.getHaVarrayConnectedToRp())) {
                            ExportGroup exportGroup =
                                    getExportGroup(psetRPSystem.get(volume.getProtectionSet().getURI()), 
                                            volume.getId(), associatedVolume.getVirtualArray(), 
                                            associatedVolume.getInternalSiteName());
                            if (exportGroup != null) {
                            	_log.info(String.format("Removing volume [%s] from export group [%s].", 
                                                        volume.getLabel(), exportGroup.getGeneratedName()));
                            }
                            // Assuming we've found the correct Export Group for this volume, let's
                            // then add the information we need to the rpExports map.
                            addExportGroup(rpExports, exportGroup, volumeURI, storageURI);
                            break;
                        }
                    }
                }
    		}
    		else if (VirtualPool.vPoolSpecifiesMetroPoint(virtualPool) && 
    				volume.getPersonality().equals((Volume.PersonalityTypes.SOURCE.toString()))) {    		   
    		    // We are dealing with a MetroPoint distributed volume so we need to get 2 export groups, one
                // export group for each cluster. 
    			if (volume.getAssociatedVolumes() != null &&
    					volume.getAssociatedVolumes().size() == 2) {    				   				
    				for (String associatedVolURI : volume.getAssociatedVolumes()) {
    					 _log.info(String.format("MetroPoint Source Volume [%s] to be removed from export group.", volume.getLabel()));
    					Volume associatedVolume = _dbClient.queryObject(Volume.class, URI.create(associatedVolURI));
    					ExportGroup exportGroup =
    		    				getExportGroup(psetRPSystem.get(volume.getProtectionSet().getURI()), 
    		    						volume.getId(), associatedVolume.getVirtualArray(), 
    		    						associatedVolume.getInternalSiteName());
    					if (exportGroup != null) {
    					_log.info(String.format("Removing volume [%s] from export group [%s].", 
                                                    volume.getLabel(), exportGroup.getGeneratedName()));
    					}
    		    		// Assuming we've found the correct Export Group for this volume, let's
    		    		// then add the information we need to the rpExports map.
    		    		addExportGroup(rpExports, exportGroup, volumeURI, storageURI);
    				}
    			}
    		} 
    		else {
    		    _log.info(String.format("Volume [%s] to be removed from export group.", volume.getLabel()));
        		// Find the Export Group for this regular RP volume
        		ExportGroup exportGroup =
        				getExportGroup(psetRPSystem.get(volume.getProtectionSet().getURI()), 
        						volume.getId(), volume.getVirtualArray(),
        						volume.getInternalSiteName());
        		
        		if(exportGroup != null) {
        			_log.info(String.format("Removing volume [%s] from export group [%s].", 
                                            volume.getLabel(), exportGroup.getGeneratedName()));
        		}
        		// Assuming we've found the correct Export Group for this volume, let's
        		// then add the information we need to the rpExports map.
        		addExportGroup(rpExports, exportGroup, volumeURI, storageURI);
    		}
    	}
				
    	// Generate the workflow steps for export volume removal and volume deletion
    	for (URI exportURI : rpExports.keySet()) {     	
    		_log.info(String.format("Export Group will have these volumes removed: [%s]", Joiner.on(',').join(rpExports.get(exportURI).getVolumes())));    		
    	    RPExport rpExport = rpExports.get(exportURI);    		
			if (!rpExport.getVolumes().isEmpty()) {
			    _exportWfUtils.generateExportGroupRemoveVolumes(workflow,
						STEP_DV_REMOVE_VOLUME_EXPORT, waitFor, rpExport.getStorageSystem(),
						exportURI, rpExport.getVolumes());		
			    returnStep = STEP_DV_REMOVE_VOLUME_EXPORT;
			}
    	}
    	
    	_log.info("Completed adding steps to remove volumes from export groups.");

    	return returnStep;
    }

    /**
     * Convenience method to add an RPExport object to the map of RPExports.
     * 
     * @param rpExports the Map we want to add to.
     * @param exportGroup the export group who's ID we want to use as the key.
     * @param volumeURI the volume we want to add to the RPExport.
     * @param storageURI the storage system.
     */
    private void addExportGroup(Map<URI, RPExport> rpExports, ExportGroup exportGroup, URI volumeURI, URI storageURI) {
		if (exportGroup != null) {    		    
		    RPExport rpExport = rpExports.get(exportGroup.getId());
		    if (rpExport == null) {
		        rpExport = new RPExport();
		        rpExport.setStorageSystem(storageURI);    		        
		        rpExports.put(exportGroup.getId(), rpExport);
		    }    		    
		    rpExport.getVolumes().add(volumeURI);		   
		}
    }
    
    /*
     * RPDeviceController.exportGroupCreate()
     *
     * This method is a mini-orchestration of all of the steps necessary to create an export based on
     * a Bourne Snapshot object associated with a RecoverPoint bookmark.
     *
     * This controller does not service block devices for export, only RP bookmark snapshots.
     *
     * The method is responsible for performing the following steps:
     * - Enable the volumes to a specific bookmark.
     * - Call the block controller to export the target volume
     *
     * @param protectionDevice The RP System used to manage the protection
     * @param exportgroupID The export group
     * @param snapshots snapshot list
     * @param initatorURIs initiators to send to the block controller
     * @param token The task object
     */
    @Override
    public void exportGroupCreate(URI protectionDevice, URI exportGroupID,
                                  List<URI> initiatorURIs, Map<URI, Integer> snapshots,
                                  String token) throws ControllerException {
        TaskCompleter taskCompleter = null;
        try {
            // Grab the RP System information; we'll need it to talk to the RP client
            ProtectionSystem rpSystem = getRPSystem(protectionDevice);      
            
            taskCompleter = new RPCGExportCompleter(exportGroupID, token);

            // Ensure the bookmarks actually exist before creating the export group
            searchForBookmarks(protectionDevice, snapshots.keySet());             
            
            //Create a new token/taskid and use that in the workflow. Multiple threads entering this method might collide with each others workflows in cassandra if the taskid is not unique.
            String newToken = UUID.randomUUID().toString();

            // Set up workflow steps.
            Workflow workflow = _workflowService.getNewWorkflow(this, "exportGroupCreate", true, newToken);

            // Tasks 1: Activate the bookmarks
            //
            // Enable image access on the target volumes
            addEnableImageAccessStep(workflow, rpSystem, snapshots, null);

            // Tasks 2: Export Volumes
            //
            // Export the volumes associated with the snapshots to the host
            addExportSnapshotSteps(workflow, rpSystem, exportGroupID, snapshots, initiatorURIs);

            // Execute the plan and allow the WorkflowExecutor to fire the taskCompleter.
            String successMessage = String.format("Workflow of Export Group %s successfully created",
                    exportGroupID);
            workflow.executePlan(taskCompleter, successMessage);
        } catch (InternalException e) {
        	_log.error("Operation failed with Exception: " , e);
        	if (taskCompleter != null)
        	    taskCompleter.error(_dbClient, e);
        } catch (Exception e) {
        	_log.error("Operation failed with Exception: " , e);
        	if (taskCompleter != null)
        	    taskCompleter.error(_dbClient, DeviceControllerException.errors.jobFailed(e));
        }
    }
    
    /**
     * Method that adds the export snapshot step.
     *
     * @param workflow workflow object
     * @param rpSystem RP system
     * @param exportGroupID export group ID
     * @param snapshots snapshots, HLUs
     * @param initiatorURIs initiators
     * @throws InternalException
     * @throws URISyntaxException 
     */
    private void addExportSnapshotSteps(Workflow workflow, ProtectionSystem rpSystem, URI exportGroupID,
            Map<URI, Integer> snapshots, List<URI> initiatorURIs) throws InternalException {
        ExportGroup exportGroup = _dbClient.queryObject(ExportGroup.class, exportGroupID);

        // Reformat the incoming arguments for the block export create call
        String exportStep = workflow.createStepId();
        initTaskStatus(exportGroup, exportStep, Operation.Status.pending, "create export");
        StorageSystem device = null;
        
        // Get the underlying block device
        for (URI snapshotID : snapshots.keySet()) {
            BlockSnapshot snapshot = _dbClient.queryObject(BlockSnapshot.class, snapshotID);

            if (device == null) {
            	device = _dbClient.queryObject(StorageSystem.class, snapshot.getStorageController());
            	break; 
            }
        }

        _log.info("Calling workflow to export (at a later time) create: {} initiators: {}" + exportGroup.getId(), initiatorURIs);
        _exportWfUtils.
                generateExportGroupCreateWorkflow(workflow, null, STEP_ENABLE_IMAGE_ACCESS, device.getId(),
                        exportGroupID, snapshots, initiatorURIs);

        _log.info("Added export group create step in workflow: " + exportGroup.getId());
    }

    /**
     * Method that adds the steps to the workflow to enable image access
     *
     * @param workflow workflow object
     * @param rpSystem RP system
     * @param snapshots snapshot map
     * @throws WorkflowException
     */
    private String addEnableImageAccessStep(Workflow workflow, ProtectionSystem rpSystem, Map<URI, Integer> snapshots, String waitFor) throws InternalException {
        String stepId = workflow.createStepId();
        Workflow.Method enableImageAccessExecuteMethod = new Workflow.Method(METHOD_ENABLE_IMAGE_ACCESS_STEP,
                rpSystem.getId(), snapshots);       
        Workflow.Method enableImageAccessExecutionRollbackMethod = new Workflow.Method(METHOD_ENABLE_IMAGE_ACCESS_ROLLBACK_STEP,
                rpSystem.getId(), snapshots, true);

        workflow.createStep(STEP_ENABLE_IMAGE_ACCESS, "Enable image access subtask for export group: " + snapshots.keySet(),
                waitFor, rpSystem.getId(), rpSystem.getSystemType(), this.getClass(),
                enableImageAccessExecuteMethod, enableImageAccessExecutionRollbackMethod, stepId);

        _log.info(
        		String.format("Added enable image access step [%s] in workflow", stepId));
        
        return STEP_ENABLE_IMAGE_ACCESS;
    }

    /**
     * Workflow step method for enabling an image access
     *
     * @param rpSystem RP system
     * @param snapshots Snapshot list to enable
     * @param token the task
     * @return true if successful
     * @throws ControllerException
     */
    public boolean enableImageAccessStep(URI rpSystemId, Map<URI, Integer> snapshots, String token) throws ControllerException {
        try {
        WorkflowStepCompleter.stepExecuting(token);
        URI device = null;
        for (URI snapshotID : snapshots.keySet()) {
            BlockSnapshot snapshot = _dbClient.queryObject(BlockSnapshot.class, snapshotID);
            if (device == null) {
                device = snapshot.getStorageController();
            }
        }

        // Enable snapshots
        if (!enableImageForSnapshots(rpSystemId, device, new ArrayList<URI>(snapshots.keySet()), token)) {
        	stepFailed(token, "enableImageAccessStep: Failed to enable image");
        	return false;
        }

        // Update the workflow state.
        WorkflowStepCompleter.stepSucceded(token);
        } catch (Exception e) {
        	stepFailed(token, "enableImageAccessStep");
            return false;
        }

        return true;
    }
    
    /**
     * Workflow rollback step method for enabling an image access
     *    
     * @param rpSystemId RP System
     * @param snapshots list of snapshots to rollback
     * @param isRollback True if this is a rollback operation. Should be true for any method calling this. 
     * @param stepId
     * @return
     * @throws ControllerException
     */
    public boolean enableImageAccessStepRollback(URI rpSystemId, Map<URI, Integer> snapshots, boolean isRollback, String stepId) throws ControllerException {
    	try {    		
    		WorkflowStepCompleter.stepExecuting(stepId);

    		// disable image access
    		disableImageForSnapshots(rpSystemId, new ArrayList<URI>(snapshots.keySet()), isRollback, stepId);

    		// Update the workflow state.
    		WorkflowStepCompleter.stepSucceded(stepId);
    	} catch (Exception e) {    		
    		return stepFailed(stepId, e, "enableImageAccessStepRollback");
    	}
    	return true;
    }
    
    /*
     * RPDeviceController.exportGroupDelete()
     *
     * This method is a mini-orchestration of all of the steps necessary to delete an export group.
     *
     * This controller does not service block devices for export, only RP bookmark snapshots.
     *
     * The method is responsible for performing the following steps:
     * - Call the block controller to delete the export of the target volumes
     * - Disable the bookmarks associated with the snapshots.
     *
     * @param protectionDevice The RP System used to manage the protection
     * @param exportgroupID The export group
     * @param token The task object associated with the volume creation task that we piggy-back our events on
     */
    @Override
    public void exportGroupDelete(URI protectionDevice, URI exportGroupID, String token) throws InternalException {
        TaskCompleter taskCompleter = null;
        try {
            // Grab the RP System information; we'll need it to talk to the RP client
            ProtectionSystem rpSystem = getRPSystem(protectionDevice);

            taskCompleter = new RPCGExportDeleteCompleter(exportGroupID, token);

            //Create a new token/taskid and use that in the workflow. Multiple threads entering this method might collide with each others workflows in cassandra if the taskid is not unique.
            String newToken = UUID.randomUUID().toString();

            // Set up workflow steps.
            Workflow workflow = _workflowService.getNewWorkflow(this, "exportGroupDelete", true, newToken);

            // Task 1: deactivate the bookmarks
            //
            // Disable image access on the target volumes
            // This is important to do first because:
            // After the export group is deleted (in the next step), we may not have access to the object.
            // If export delete itself were to fail, it's good that we at least got this step done.  Easier to remediate.
            addDisableImageAccessSteps(workflow, rpSystem, exportGroupID);

            // Task 2: Export Delete Volumes
            //
            // Delete of the export group with the volumes associated with the snapshots to the host
            addExportSnapshotDeleteSteps(workflow, rpSystem, exportGroupID);


            // Execute the plan and allow the WorkflowExecutor to fire the taskCompleter.
            String successMessage = String.format("Workflow of Export Group %s Delete successfully created",
                    exportGroupID);
            workflow.executePlan(taskCompleter, successMessage);

        } catch (InternalException e) {
        	_log.error("Operation failed with Exception: " , e);
            if (taskCompleter != null)
                taskCompleter.error(_dbClient, e);
        } catch (Exception e) {
        	_log.error("Operation failed with Exception: " , e);
            if (taskCompleter != null)
                taskCompleter.error(_dbClient, DeviceControllerException.errors.jobFailed(e));
        }
    }

    /**
     * @param storageURI
     * @param exportGroupURI
     * @param initiators
     * @param token          
     */
    @Override
    public void exportGroupAddInitiators(URI storageURI, URI exportGroupURI, List<URI> initiators, String token) throws InternalException {
        WorkflowStepCompleter.stepFailed(token, DeviceControllerErrors.recoverpoint
                .rpNotSupportExportGroupInitiatorsAddOperation());
    }

    /**
     * @param storageURI
     * @param exportGroupURI
     * @param initiators
     * @param token          
     */
    @Override
    public void exportGroupRemoveInitiators(URI storageURI, URI exportGroupURI, List<URI> initiators, String token) throws InternalException {
        WorkflowStepCompleter.stepFailed(token, DeviceControllerErrors.recoverpoint
                .rpNotSupportExportGroupInitiatorsRemoveOperation());
    }

    /**
     * Method that adds the export snapshot delete step.
     *
     * @param workflow workflow object
     * @param rpSystem RP system
     * @param exportGroupID export group ID
     * @throws InternalException
     */
    private void addExportSnapshotDeleteSteps(Workflow workflow, ProtectionSystem rpSystem, URI exportGroupID) throws InternalException {
        ExportGroup exportGroup = _dbClient.queryObject(ExportGroup.class, exportGroupID);

        // Collect all of the information needed to assemble a step for the workflow
        String exportStep = workflow.createStepId();
        initTaskStatus(exportGroup, exportStep, Operation.Status.pending, "export delete");
        StorageSystem device = null;
        for (String volumeIDString : exportGroup.getVolumes().keySet()) {
            URI blockID;
            try {
                blockID = new URI(volumeIDString);
                BlockObject block = BlockObject.fetch(_dbClient, blockID);
                if (block.getProtectionController()!=null && device == null) {
                    device = _dbClient.queryObject(StorageSystem.class, block.getStorageController());
                }
            } catch (URISyntaxException e) {
                _log.error("Couldn't find volume ID for export delete: " + volumeIDString, e);
                // continue
            }
        }

        _log.info("Calling workflow to export (at a later time) delete: {}" + exportGroup.getId());
        _exportWfUtils.
                generateExportGroupDeleteWorkflow(workflow, STEP_EXPORT_DELETE_SNAPSHOT, STEP_EXPORT_GROUP_DELETE, device.getId(),
                        exportGroupID);
        _log.info("Created export group delete step in workflow: " + exportGroup.getId());
    }
    
    
    
    /* Method that adds the steps to the workflow to disable image access (for BLOCK snapshots)
     *
	 * @param workflow Workflow
	 * @param waitFor waitFor step id
	 * @param snapshots list of snapshot to disable
	 * @param rpSystem RP system
	 * @throws InternalException
	 */
	private void addBlockSnapshotDisableImageAccessStep(Workflow workflow, String waitFor, List<URI> snapshots, ProtectionSystem rpSystem) 
		   throws InternalException {
       String stepId = workflow.createStepId();
       
       Workflow.Method disableImageAccessExecuteMethod = new Workflow.Method(METHOD_SNAPSHOT_DISABLE_IMAGE_ACCESS_SINGLE_STEP,  
    		   													rpSystem.getId(), snapshots, false);
       
       workflow.createStep(STEP_DISABLE_IMAGE_ACCESS, "Disable image access subtask for snapshots ",
               waitFor, rpSystem.getId(), rpSystem.getSystemType(), this.getClass(),
               disableImageAccessExecuteMethod, null, stepId);

       _log.info(
       		String.format("Added block snapshot disable access step [%s] in workflow", stepId));       
	}

   	/**
   	 * Workflow step method for disabling an image access
	 * @param rpSystemId RP system
	 * @param snapshots List of snapshot URIs
	 * @param isRollback true if this method is invoked as part of rollback, false otherwise
	 * @param token step Id
	 * @return
	 * @throws ControllerException
	 */
	public boolean snapshotDisableImageAccessSingleStep(URI rpSystemId, List<URI> snapshots, boolean isRollback, String token) 
			throws ControllerException {
       try {
	    	WorkflowStepCompleter.stepExecuting(token);	
	    	disableImageForSnapshots(rpSystemId, snapshots, isRollback, token);	    
	        // Update the workflow state.
	    	WorkflowStepCompleter.stepSucceded(token);
	   	} catch (Exception e) {
	   		_log.error(String.format("snapshotDisableImageAccessSingleStep Failed - Protection System: %s",
	   				String.valueOf(rpSystemId)));
	   		return stepFailed(token, e, "snapshotDisableImageAccessSingleStep");
	   	}
	       
	   	return true;
	} 

    /**
     * Method that adds the steps to the workflow to disable image access
     *
     * @param workflow workflow object
     * @param rpSystem RP system
     * @param exportGroupID export group ID
     * @throws WorkflowException
     */
    private void addDisableImageAccessSteps(Workflow workflow, ProtectionSystem rpSystem, URI exportGroupID) throws WorkflowException {
        String stepId = workflow.createStepId();

        Workflow.Method disableImageAccessExecuteMethod = new Workflow.Method(METHOD_DISABLE_IMAGE_ACCESS_STEP,
                rpSystem.getId(), exportGroupID);

        workflow.createStep(STEP_EXPORT_GROUP_DELETE, "Disable image access subtask for export group: " + exportGroupID,
                null, rpSystem.getId(), rpSystem.getSystemType(), this.getClass(),
                disableImageAccessExecuteMethod, null, stepId);

        _log.info(String.format("Added disable image access step [%s] in workflow", stepId));
    }
    
    /**
     * Workflow step method for disabling an image access of all snapshots in an export group
     *
     * @param rpSystem RP system
     * @param token the task
     * @return true if successful
     * @param exportGroupID export group ID
     * @throws ControllerException
     */
    public boolean disableImageAccessStep(URI rpSystemId, URI exportGroupURI, String token) throws ControllerException {
    	try {
    		WorkflowStepCompleter.stepExecuting(token);

    		List<URI> snapshots = new ArrayList<URI>();
    		// In order to find all of the snapshots to deactivate, go through the devices, find the RP snapshots, and deactivate any active ones
    		ExportGroup exportGroup = _dbClient.queryObject(ExportGroup.class, exportGroupURI);
    		for (String exportVolumeIDStr : exportGroup.getVolumes().keySet()) {
    			URI blockID;
    			blockID = new URI(exportVolumeIDStr);
    			BlockObject block = BlockObject.fetch(_dbClient, blockID);
    			if (block.getProtectionController()!=null) {
    				if (block.getId().toString().contains("BlockSnapshot")) {
    					// Collect this snapshot; it needs to be disabled
    					snapshots.add(block.getId());
    				}
    			}
    		}

    		disableImageForSnapshots(rpSystemId, new ArrayList<URI>(snapshots), false, token);

    		// Update the workflow state.
    		WorkflowStepCompleter.stepSucceded(token);
    	} catch (Exception e) {
    		_log.error(String.format("disableImageAccessStep Failed - Protection System: %s, export group: %s",
    				String.valueOf(rpSystemId), String.valueOf(exportGroupURI)));
    		return stepFailed(token, e, "disableImageAccessStep");
    	}
    	return true;
    }
    
    /**
     * Add steps to disable image access
     *
     * @param workflow workflow object
     * @param rpSystem protection system
     * @param exportGroupID export group ID
     * @param snapshotIDs snapshot ID
     * @throws InternalException
     */
    private void addDisableImageAccessSteps(Workflow workflow,
                                            ProtectionSystem rpSystem,
                                            URI exportGroupID,
                                            List<URI> snapshotIDs) throws
            InternalException {
        String stepId = workflow.createStepId();

        Workflow.Method disableImageAccessExecuteMethod = new Workflow.Method(METHOD_DISABLE_IMAGE_ACCESS_SINGLE_STEP,
                rpSystem.getId(), exportGroupID, snapshotIDs, false);

        workflow.createStep(STEP_EXPORT_GROUP_DISABLE, "Disable image access subtask for export group: " + exportGroupID,
                null, rpSystem.getId(), rpSystem.getSystemType(), this.getClass(),
                disableImageAccessExecuteMethod, null, stepId);

        _log.info(String.format("Added disable image access step [%s] in workflow", stepId));
    }
    
    /**
     * Workflow step method for disabling an image access
     * 
     * @param rpSystemId RP system URI
     * @param exportGroupURI ExportGroup URI
     * @param snapshots list of snapshots to disable
     * @param isRollback True if this is invoked as part of a rollback operation.
     * @param token
     * @return boolean
     * @throws ControllerException
     */
    public boolean disableImageAccessSingleStep(URI rpSystemId, URI exportGroupURI, List<URI> snapshots, boolean isRollback, String token) throws ControllerException {
        try {
	    	WorkflowStepCompleter.stepExecuting(token);
	
	    	disableImageForSnapshots(rpSystemId, snapshots, isRollback, token);
	    
	        // Update the workflow state.
	    	WorkflowStepCompleter.stepSucceded(token);
    	} catch (Exception e) {
    		_log.error(String.format("disableImageAccessSingleStep Failed - Protection System: %s, export group: %s",
    				String.valueOf(rpSystemId), String.valueOf(exportGroupURI)));
    		return stepFailed(token, e, "disableImageAccessSingleStep");
    	}
        
    	return true;
    }  
    /*
     * RPDeviceController.exportAddVolume()
     *
     * This method is a mini-orchestration of all of the steps necessary to add a volume to an export group
     * that is based on a Bourne Snapshot object associated with a RecoverPoint bookmark.
     *
     * This controller does not service block devices for export, only RP bookmark snapshots.
     *
     * The method is responsible for performing the following steps:
     * - Enable the volumes to a specific bookmark.
     * - Call the block controller to export the target volume
     *
     * @param protectionDevice The RP System used to manage the protection
     * @param exportGroupID The export group
     * @param snapshot RP snapshot
     * @param lun HLU
     * @param token The task object associated with the volume creation task that we piggy-back our events on
     */
    @Override
    public void exportGroupAddVolumes(URI protectionDevice, URI exportGroupID,
                                      Map<URI, Integer> snapshots, String token) throws
            InternalException {
        TaskCompleter taskCompleter = null;
        try {
            // Grab the RP System information; we'll need it to talk to the RP client
            ProtectionSystem rpSystem = getRPSystem(protectionDevice);

            taskCompleter = new RPCGExportCompleter(exportGroupID, token);

            // Ensure the bookmarks actually exist before creating the export group
            searchForBookmarks(protectionDevice, snapshots.keySet()); 
            
            //Create a new token/taskid and use that in the workflow. Multiple threads entering this method might collide with each others workflows in cassandra if the taskid is not unique.
            String newToken = UUID.randomUUID().toString();

            // Set up workflow steps.
            Workflow workflow = _workflowService.getNewWorkflow(this, "exportGroupAddVolume", true, newToken);

            // Tasks 1: Activate the bookmark
            //
            // Enable image access on the target volume
            addEnableImageAccessStep(workflow, rpSystem, snapshots, null);

            // Tasks 2: Export Volumes
            //
            // Export the volumes associated with the snapshots to the host
            addExportAddVolumeSteps(workflow, rpSystem, exportGroupID, snapshots);

            // Execute the plan and allow the WorkflowExecutor to fire the taskCompleter.
            String successMessage = String.format("Workflow of Export Group %s Add Volume successfully created",
                    exportGroupID);
            workflow.executePlan(taskCompleter, successMessage);
        } catch (InternalException e) {
        	_log.error("Operation failed with Exception: " , e);
            if (taskCompleter != null)
                taskCompleter.error(_dbClient, e);
        } catch (Exception e) {
        	_log.error("Operation failed with Exception: " , e);
            if (taskCompleter != null)
                taskCompleter.error(_dbClient, DeviceControllerException.errors.jobFailed(e));
        }
    }

    /**
     * Method that adds the export snapshot step for a single volume
     *
     * @param workflow workflow object
     * @param rpSystem RP system
     * @param exportGroupID export group ID
     * @param snapshotID snapshot ID
     * @param hlu host logical unit
     * @throws InternalException
     */
    private void addExportAddVolumeSteps(Workflow workflow, ProtectionSystem rpSystem,
                                         URI exportGroupID,
                                         Map<URI, Integer> snapshots) throws
            InternalException {
        ExportGroup exportGroup = _dbClient.queryObject(ExportGroup.class, exportGroupID);

        String exportStep = workflow.createStepId();
        initTaskStatus(exportGroup, exportStep, Operation.Status.pending, "export add volume");

        Map<URI, Map<URI, Integer>> deviceToSnapshots =
                new HashMap<URI, Map<URI, Integer>>();
        for (Map.Entry<URI, Integer> snapshotEntry : snapshots.entrySet()) {
            BlockSnapshot snapshot =
                    _dbClient.queryObject(BlockSnapshot.class, snapshotEntry.getKey());
            Map<URI, Integer> map =
                    deviceToSnapshots.get(snapshot.getStorageController());
            if (map == null) {
                map = new HashMap<URI, Integer>();
                deviceToSnapshots.put(snapshot.getStorageController(), map);
            }
            map.put(snapshot.getId(), snapshotEntry.getValue());
        }

        for (Map.Entry<URI, Map<URI, Integer>> deviceEntry :
                deviceToSnapshots.entrySet()) {
            _log.info(String.format("Calling workflow to export %s (at a later time) using %s to add %s ",
            		exportGroup.getId(),
            		deviceEntry.getKey(),
            		Joiner.on(',').join(deviceEntry.getValue().keySet())));
            _exportWfUtils.generateExportGroupAddVolumes(workflow, null, STEP_ENABLE_IMAGE_ACCESS,
            		deviceEntry.getKey(), exportGroupID, deviceEntry.getValue());
        }

        _log.info("export group add volume step in workflow: " + exportGroup.getId());
    }

    /*
     * RPDeviceController.exportRemoveVolume()
     *
     * This method is a mini-orchestration of all of the steps necessary to remove an RP volume from an export group.
     *
     * This controller does not service block devices for export, only RP bookmark snapshots.
     *
     * The method is responsible for performing the following steps:
     * - Call the block controller to delete the export of the target volume
     * - Disable the bookmarks associated with the snapshot.
     *
     * @param protectionDevice The RP System used to manage the protection
     * @param exportgroupID The export group
     * @param snapshotID snapshot ID to remove
     * @param token The task object
     */
    @Override
    public void exportGroupRemoveVolumes(URI protectionDevice, URI exportGroupID,
                                         List<URI> snapshotIDs,
                                         String token) throws InternalException {
        TaskCompleter taskCompleter = null;
        try {
            // Grab the RP System information; we'll need it to talk to the RP client
            ProtectionSystem rpSystem = getRPSystem(protectionDevice);

            taskCompleter = new RPCGExportDeleteCompleter(exportGroupID, token);

            //Create a new token/taskid and use that in the workflow. Multiple threads entering this method might collide with each others workflows in cassandra if the taskid is not unique.
            String newToken = UUID.randomUUID().toString();

            // Set up workflow steps.
            Workflow workflow = _workflowService.getNewWorkflow(this, "exportRemoveVolume", true, newToken);

            // Task 1: deactivate the bookmark
            //
            // Disable image access on the target volumes
            // We want to run this first so we at least get the target volume freed-up, even if
            // the export remove fails.
            addDisableImageAccessSteps(workflow, rpSystem, exportGroupID, snapshotIDs);

            // Task 2: Export Volume removal
            //
            // Export the volumes associated with the snapshots to the host
            addExportRemoveVolumeSteps(workflow, rpSystem, exportGroupID, snapshotIDs);

            // Execute the plan and allow the WorkflowExecutor to fire the taskCompleter.
            String successMessage = String.format("Workflow of Export Group %s Remove Volume successfully created",
                    exportGroupID);
            workflow.executePlan(taskCompleter, successMessage);

        } catch (InternalException e) {
        	_log.error("Operation failed with Exception: " , e);
            if (taskCompleter != null)
                taskCompleter.error(_dbClient, e);
        } catch (Exception e) {
        	_log.error("Operation failed with Exception: " , e);
            if (taskCompleter != null)
                taskCompleter.error(_dbClient, DeviceControllerException.errors.jobFailed(e));
        }
    }

    /**
     * Add the export remove volume step to the workflow
     *
     * @param workflow workflow object
     * @param rpSystem protection system
     * @param exportGroupID export group
     * @param boIDs volume/snapshot IDs
     * @throws InternalException
     */
    private void addExportRemoveVolumeSteps(Workflow workflow,
                                            ProtectionSystem rpSystem,
                                            URI exportGroupID,
                                            List<URI> boIDs) throws InternalException {
        ExportGroup exportGroup = _dbClient.queryObject(ExportGroup.class, exportGroupID);

        String exportStep = workflow.createStepId();
        initTaskStatus(exportGroup, exportStep, Operation.Status.pending,
                "export remove volumes (that contain RP snapshots)");
        Map<URI, List<URI>> deviceToSnapshots = new HashMap<URI, List<URI>>();
        for (URI snapshotID : boIDs) {
            BlockSnapshot snapshot = _dbClient.queryObject(BlockSnapshot.class, snapshotID);
            List<URI> snaps = deviceToSnapshots.get(snapshot.getStorageController());
            if (snaps == null) {
                snaps = new ArrayList<URI>();
                deviceToSnapshots.put(snapshot.getStorageController(), snaps);
            }
            snaps.add(snapshotID);
        }
        _log.info("Calling workflow to export (at a later time) remove snapshot: {}", exportGroup.getId());
        for (Map.Entry<URI, List<URI>> deviceEntry : deviceToSnapshots.entrySet()) {
            _exportWfUtils.
                    generateExportGroupRemoveVolumes(workflow, STEP_EXPORT_REMOVE_SNAPSHOT, STEP_EXPORT_GROUP_DISABLE,
                            deviceEntry.getKey(), exportGroupID, deviceEntry.getValue());
        }

        _log.info("Created export group remove snapshot step in workflow: " + exportGroup.getId());
    }



    /**
     * Update the params objects with the proper WWN information so the CG can be created.
     *
     * @param params cg params
     * @throws InternalException
     */
    private void updateCGParams(CGRequestParams params) throws InternalException {
        for (CreateCopyParams copy : params.getCopies()) {
            _log.info("View copy: " + copy.getName());
            // Fill the map with varray
            for (CreateVolumeParams volume : copy.getJournals()) {
                Volume dbVolume = _dbClient.queryObject(Volume.class, volume.getVolumeURI());
                volume.setWwn(dbVolume.getWWN());
            }
        }

        for (CreateRSetParams rset : params.getRsets()) {
            _log.info("View rset: " + rset.getName());
            for (CreateVolumeParams volume : rset.getVolumes()) {
                Volume dbVolume = _dbClient.queryObject(Volume.class, volume.getVolumeURI());
                volume.setWwn(dbVolume.getWWN());
            }
        }
    }

    /**
     * Lock the entire CG based on this volume.
     * 
     * @param volumeId volume whose CG we wish to lock
     * @return true if the lock succeeded, false otherwise
     */
    private void lockCG(TaskLockingCompleter completer) throws DeviceControllerException {
    	if (!completer.lockCG(_dbClient, _locker)) {
    		// Gather information necessary to give a good error message... 
    		Volume volume = _dbClient.queryObject(Volume.class, completer.getId());
    		if (volume != null) {
    			if (volume.getProtectionController() != null && volume.getProtectionSet() != null) {
    				ProtectionSystem rpSystem = _dbClient.queryObject(ProtectionSystem.class, volume.getProtectionController());
    				if (volume.getProtectionSet() != null) {
    					ProtectionSet protectionSet = _dbClient.queryObject(ProtectionSet.class, volume.getProtectionSet());
        				if (rpSystem != null && protectionSet != null && rpSystem.getInstallationId() != null && protectionSet.getLabel() != null) {
        					throw DeviceControllerExceptions.recoverpoint.anotherOperationInProgress(rpSystem.getLabel(), protectionSet.getLabel());
        				}
    				} else {
    					throw DeviceControllerExceptions.recoverpoint.anotherOperationInProgress(rpSystem.getLabel(), "No protection set");
    				}
    			}
    		}
        	throw DeviceControllerExceptions.recoverpoint.notAllObjectsCouldBeRetrieved(completer.getId());
    	}
    }
	
	/**
	 * RP specific workflow steps required prior to expanding the underlying volume are added here.
	 * Ex. RP CG remove replication sets.
	 * 
	 * @param workflow
	 * @param volURI
	 * @param expandVolURIs
	 * @param taskId
	 * @return
	 * @throws WorkflowException
	 */
	public String addPreVolumeExpandSteps(Workflow workflow, List<VolumeDescriptor> volumeDescriptors, String taskId) 
    		throws WorkflowException {
				
		// Just grab a legit target volume that already has an assigned protection controller.  
		// This will work for all operations, adding, removing, vpool change, etc.
        List<VolumeDescriptor> protectionControllerDescriptors = VolumeDescriptor.filterByType(volumeDescriptors, 
                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.RP_TARGET,  
        									  VolumeDescriptor.Type.RP_VPLEX_VIRT_TARGET },
                new VolumeDescriptor.Type[] { });
        // If there are no RP volumes, just return
        if (protectionControllerDescriptors.isEmpty()) return null;

        // Grab any volume from the list so we can grab the protection system, which will be the same for all volumes.
    	Volume volume = _dbClient.queryObject(Volume.class, protectionControllerDescriptors.get(0).getVolumeURI());
		ProtectionSystem rpSystem = _dbClient.queryObject(ProtectionSystem.class, volume.getProtectionController());

		// Get only the RP volumes from the descriptors.
        List<VolumeDescriptor> volumeDescriptorsTypeFilter = VolumeDescriptor.filterByType(volumeDescriptors, 
                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.RP_SOURCE, 		        											        		                             
        		                              VolumeDescriptor.Type.RP_EXISTING_SOURCE,
        		                              VolumeDescriptor.Type.RP_VPLEX_VIRT_SOURCE,}, 
                new VolumeDescriptor.Type[] { });
        // If there are no RP volumes, just return
        if (volumeDescriptorsTypeFilter.isEmpty()) return null;
		        		
			
		for (VolumeDescriptor descriptor : volumeDescriptorsTypeFilter) {
			URI volURI = descriptor.getVolumeURI();
			ProtectionSystem rp = _dbClient.queryObject(ProtectionSystem.class, volume.getProtectionController());		
	        String stepId = workflow.createStepId();
	        Workflow.Method deleteRsetExecuteMethod = new Workflow.Method(METHOD_DELETE_RSET_STEP,
	                rpSystem.getId(), volURI);
	
	        workflow.createStep(STEP_PRE_VOLUME_EXPAND, "Pre volume expand, delete replication set subtask for RP: " + volURI.toString(),
	        		null, rpSystem.getId(), rp.getSystemType(), this.getClass(),
	                deleteRsetExecuteMethod, null, stepId);
	
	        _log.info("addPreVolumeExpandSteps Replication Set in workflow");
		}
        return STEP_PRE_VOLUME_EXPAND;
    }

    private RecreateReplicationSetRequestParams getReplicationSettings(ProtectionSystem rpSystem, URI volumeId) throws RecoverPointException {
    	RecoverPointClient rp = RPHelper.getRecoverPointClient(rpSystem);
		Volume volume = _dbClient.queryObject(Volume.class, volumeId);
    	RecoverPointVolumeProtectionInfo volumeProtectionInfo = rp.getProtectionInfoForVolume(volume.getWWN());
    	return rp.getReplicationSet(volumeProtectionInfo);
	}

    /**
     * RP specific workflow steps after volume expansion are added here in this method
     * RP CG replication sets that were removed during pre expand are reconstructed with the new expanded volumes.
     * 
     * @param workflow
     * @param waitFor
     * @param volume descriptors
     * @param taskId
     * @return
     * @throws WorkflowException
     */
    public String addPostVolumeExpandSteps(Workflow workflow, String waitFor,  List<VolumeDescriptor> volumeDescriptors, String taskId) 
    		throws WorkflowException {
    	   
    	// Get only the RP volumes from the descriptors.
        List<VolumeDescriptor> volumeDescriptorsTypeFilter = VolumeDescriptor.filterByType(volumeDescriptors, 
                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.RP_SOURCE,         									
        		                              VolumeDescriptor.Type.RP_EXISTING_SOURCE,
        		                              VolumeDescriptor.Type.RP_VPLEX_VIRT_SOURCE}, 
                new VolumeDescriptor.Type[] { });
        // If there are no RP volumes, just return
        if (volumeDescriptorsTypeFilter.isEmpty()) return waitFor;
        
    	for (VolumeDescriptor descriptor : volumeDescriptorsTypeFilter) {
    		Volume volume = _dbClient.queryObject(Volume.class, descriptor.getVolumeURI());
	    	ProtectionSystem rpSystem =  _dbClient.queryObject(ProtectionSystem.class, volume.getProtectionController());
	    	// Get the replication set settings
			RecreateReplicationSetRequestParams rsetParams = getReplicationSettings(rpSystem, volume.getId());
			
	        String stepId = workflow.createStepId();
	        Workflow.Method recreateRSetExecuteMethod = new Workflow.Method(METHOD_RECREATE_RSET_STEP,
	                rpSystem.getId(), volume.getId(), rsetParams);
	
	        workflow.createStep(STEP_POST_VOLUME_EXPAND, "Post volume Expand, Recreate replication set subtask for RP: " + volume.toString(),
	        		waitFor, rpSystem.getId(), rpSystem.getSystemType(), this.getClass(),
	                recreateRSetExecuteMethod, null, stepId);
	
	        _log.info("Recreate Replication Set in workflow");
    	}
        return STEP_POST_VOLUME_EXPAND;
    }

    /**
     * Delete the replication set
     *
     * @param rpSystem RP system
     * @param params parameters needed to create the CG
     * @param token the task
     * @return
     * @throws InternalException 
     */
    public boolean deleteRSetStep(URI rpSystemId, URI volumeId, String token) throws InternalException {
        Volume volume = _dbClient.queryObject(Volume.class, volumeId);

        try {
        	ProtectionSystem rpSystem = _dbClient.queryObject(ProtectionSystem.class, rpSystemId);
            RecoverPointClient rp = RPHelper.getRecoverPointClient(rpSystem);
            RecoverPointVolumeProtectionInfo volumeProtectionInfo = rp.getProtectionInfoForVolume(volume.getWWN());
            rp.deleteReplicationSet(volumeProtectionInfo);

            // Update the workflow state.
            WorkflowStepCompleter.stepSucceded(token);
		} catch (Exception e) {
    		_log.error(String.format("deleteRSetStep Failed - Replication Set: %s", volume.getRSetName()));
    		return stepFailed(token, e, "deleteRSetStep");
        }
        return true;
    }

    /**
     * Recreate the replication set
     *
     * @param rpSystem RP system
     * @param params parameters needed to create the CG
     * @param token the task
     * @return
     * @throws InternalException 
     */
    public boolean recreateRSetStep(URI rpSystemId, URI volumeId, RecreateReplicationSetRequestParams rsetParams, String token) throws InternalException {
    	Volume volume = _dbClient.queryObject(Volume.class, volumeId);

        try {
        	ProtectionSystem rpSystem = _dbClient.queryObject(ProtectionSystem.class, rpSystemId);
            RecoverPointClient rp = RPHelper.getRecoverPointClient(rpSystem);
            _log.info("Sleeping for 15 seconds before rescanning bus to account for latencies after expanding volume");
            try {
            	Thread.sleep(15000);
            } catch (InterruptedException e) {
            	_log.warn("Thread sleep interrupted.  Allowing to continue without sleep");
            }
                        
            rp.recreateReplicationSet(volume.getWWN(), rsetParams);

            // Update the workflow state.
            WorkflowStepCompleter.stepSucceded(token);
		} catch (Exception e) {
    		_log.error(String.format("recreateRSetStep Failed - Replication Set: %s", volume.getRSetName()));
    		return stepFailed(token, e, "recreateRSetStep");
        }
        return true;
    }

    /**
     * Create a protection set in the database that corresponds to this CG.
     *
     * @param params CG params object
     * @throws InternalException
     */
    private ProtectionSet createProtectionSet(ProtectionSystem rpSystem, CGRequestParams params) throws InternalException {
        ProtectionSet protectionSet = new ProtectionSet();

        protectionSet.setProtectionSystem(rpSystem.getId());
        protectionSet.setLabel(params.getCgName());
        protectionSet.setProtectionStatus(ProtectionStatus.ENABLED.toString());
        protectionSet.setId(URIUtil.createId(ProtectionSet.class));
        _dbClient.createObject(protectionSet);

        protectionSet = updateProtectionSet(protectionSet, params);
        
        return protectionSet;
    }

    /**
     * Update a protection set in the database that corresponds to the CG.
     * 
     * BH Note: Currently this only supports adding to the protection set. We may eventually
     * want to support removal as well.
     *
     * @param params CG params object
     * @throws InternalException
     */
    private ProtectionSet updateProtectionSet(ProtectionSet protectionSet, CGRequestParams params) throws InternalException {
        StringSet protectionSetVolumes = new StringSet();
        _log.info(String.format("Updating protection set [%s]", protectionSet.getLabel()));
        // Loop through the RSet volumes to update the protection set info and potentially add the volume to the 
        // protection set
        for (CreateRSetParams rset : params.getRsets()) {
            for (CreateVolumeParams volume : rset.getVolumes()) {
                if (protectionSet.getVolumes() != null 
                        && protectionSet.getVolumes().contains(volume.getVolumeURI().toString())) {
                    // Protection Set already has a reference to this volume, continue.
                    continue;
                }
                else {
                    Volume vol  = _dbClient.queryObject(Volume.class, volume.getVolumeURI());
                    // Set the project of the Protection Set from the volume if it
                    // hasn't already been set.
                    if (protectionSet.getProject() == null) {
                        protectionSet.setProject(vol.getProject().getURI());
                    }
                    vol.setProtectionSet(new NamedURI(protectionSet.getId(), vol.getLabel()));
                    vol.setInternalSiteName(volume.getInternalSiteName());
                    if (vol.getPersonality().equals(Volume.PersonalityTypes.SOURCE.toString())) {
                    	vol.setAccessState(Volume.VolumeAccessState.READWRITE.name());
                    	vol.setLinkStatus(Volume.LinkStatus.IN_SYNC.name());
                    } else if (vol.getPersonality().equals(Volume.PersonalityTypes.TARGET.toString())) {
                    	vol.setAccessState(Volume.VolumeAccessState.NOT_READY.name());
                    	vol.setLinkStatus(Volume.LinkStatus.IN_SYNC.name());
                    }
                    _dbClient.persistObject(vol);
                    protectionSetVolumes.add(vol.getId().toString());
                    _log.info(String.format("Adding volume [%s] to protection set [%s]", vol.getLabel(), protectionSet.getLabel()));
                }
            }
        }
        
        // Loop through the Copy volumes to update the protection set info and potentially add the volume to the 
        // protection set
        for (CreateCopyParams copy : params.getCopies()) {
            for (CreateVolumeParams volume : copy.getJournals()) {
                if (protectionSet.getVolumes() != null 
                        && protectionSet.getVolumes().contains(volume.getVolumeURI().toString())) {
                    // Protection Set already has a reference to this volume, continue.
                    continue;
                }
                else {
                    Volume vol  = _dbClient.queryObject(Volume.class, volume.getVolumeURI());
                    vol.setProtectionSet(new NamedURI(protectionSet.getId(), vol.getLabel()));
                    vol.setInternalSiteName(volume.getInternalSiteName());
                    vol.setAccessState(Volume.VolumeAccessState.NOT_READY.name());
                    _dbClient.persistObject(vol);
                    protectionSetVolumes.add(vol.getId().toString());
                    _log.info(String.format("Adding volume [%s] to protection set [%s]", vol.getLabel(), protectionSet.getLabel()));
                }
            }
        }
        
        if (protectionSet.getVolumes() == null) {
            protectionSet.setVolumes(protectionSetVolumes);
        } else {
            protectionSet.getVolumes().addAll(protectionSetVolumes);
        }
        
        _dbClient.persistObject(protectionSet);

        return protectionSet;
    }    
    
    private ProtectionSystem getRPSystem(URI protectionDevice)
            throws InternalException {
        ProtectionSystem rpSystem = _dbClient.queryObject(ProtectionSystem.class, protectionDevice);
        // Verify non-null storage device returned from the database client.
        if (rpSystem == null) {
            throw DeviceControllerExceptions.recoverpoint.failedConnectingForMonitoring(protectionDevice);
        }
        return rpSystem;
    }

    /**
     * Find the export group associated with this volume and this protection system.   
     * @param rpSystem
     * @param volumeUri
     * @param virtualArrayUri
     * @param internalSiteName
     * @return
     * @throws InternalException
     */
    private ExportGroup getExportGroup(ProtectionSystem rpSystem, URI volumeUri, 
    		URI virtualArrayUri, String internalSiteName) throws InternalException {
        _log.info(String.format("getExportGroup start: for volume %s - internal site name %s - va %s", volumeUri, internalSiteName, virtualArrayUri.toString()));
                    
        
        // Get all exportGroups that this "volumeUri" is a part of. 
        URIQueryResultList exportGroupURIs = new URIQueryResultList();
        _dbClient.queryByConstraint(ContainmentConstraint.Factory.getBlockObjectExportGroupConstraint(volumeUri), exportGroupURIs);
                              
        for (URI exportURI : exportGroupURIs) {        
            ExportGroup exportGroup = _dbClient.queryObject(ExportGroup.class, exportURI);
            if (exportGroup == null || exportGroup.getVolumes() == null) {            	
                continue;
            }
                    
            // The Export Group we're looking for will have:
            // 1. The Volume associated to it.
            // 2. Have the same Virtual Array as the Volume.
            // 3. Have the Initiators for the Volumes RP internal site in it.       
            if (exportGroup.getVolumes().containsKey(volumeUri.toString())
                    && exportGroup.getVirtualArray().equals(virtualArrayUri)) {
              
                // Get the Initiators from the Export Group
                List<String> exportWWNs = new ArrayList<String>();                  
                if (exportGroup.getInitiators() != null) {
                    for (String exportWWN : exportGroup.getInitiators()) {
                        URI exportWWNURI = URI.create(exportWWN);
                        Initiator initiator = _dbClient.queryObject(Initiator.class, exportWWNURI);
                        exportWWNs.add(initiator.getInitiatorNode());
                        exportWWNs.add(initiator.getInitiatorPort());
                    }
                }                
                
                // Get the Initiators from the Protection System for the Volumes RP internal site                
                // NOTE: Sometimes the URI is still in the DB, but the object isn't.  (I found this happens when a previous create export group
                // workflow failed.  It creates the object in the DB but it subsequently gets deleted)
                StringSet rpWWNs = rpSystem.getSiteInitiators().get(internalSiteName);
                if (rpWWNs == null) {
                    _log.error("Couldn't find site initiators for rp cluster: " + internalSiteName);
                    _log.error("RP Site Initiators: {}" + rpSystem.getSiteInitiators().toString());
                    return null;
                }                                      
               
                // Check to see if the Export Group has at least one of the RP Initiators we're looking for, if so, return
                // the Export Group
                for (String rpWWN : rpWWNs) {                    	
                	for (String exportWWN : exportWWNs) {                    		
                		if(exportWWN.equalsIgnoreCase(rpWWN)) {
                    	_log.info(String.format("Found exportGroup matching varray and rpSite for volume %s : %s - %s", volumeUri.toString(), exportGroup.getGeneratedName(), exportGroup.getLabel()));
                        return exportGroup;
                		}
                    }
                }
            }
        }
        _log.info("getExportGroup: group does NOT exist");       
        return null;
    }

    /**
     * Using the passed in export group, try to find a matching one based on generated name. Return
     * null if it can't be found meaning we should create a new one.
     * 
     * @param exportGroupToFind The export group to find
     * @return The found export group, or null if it doesn't exist
     * @throws InternalException
     */
    private ExportGroup exportGroupExistsInDB (ExportGroup exportGroupToFind) throws InternalException {       
        // Query for all existing Export Groups, a little expensive.
        List<URI> allActiveExportGroups = _dbClient.queryByType(ExportGroup.class, true);
        for (URI exportGroupURI : allActiveExportGroups) {
            ExportGroup exportGroup = _dbClient.queryObject(ExportGroup.class, exportGroupURI);
            // Sometimes the URI is still in the DB, but the object isn't is marked for deletion so 
            // we need to check to see if it's active as well if the names match. Also make sure
            // it's for the same project.
            if (exportGroup != null 
                    && !exportGroup.getInactive()                                     	                       
                    && exportGroup.getProject().getURI().equals(exportGroupToFind.getProject().getURI())) {
                // Ensure backwards compatibility by formatting the existing generated name to the same as the 
                // potential new one.
                // We're looking for a format of: 
                // rpSystem.getNativeGuid() + "_" + storageSystem.getLabel() + "_" + rpSiteName + "_" + varray.getLabel()
                // and replacing all non alpha-numerics with "" (except "_").
                String generatedName = exportGroup.getGeneratedName().trim().replaceAll("[^A-Za-z0-9_]", "");                
                if (generatedName.equals(exportGroupToFind.getGeneratedName())) {                
                    _log.info("Export Group already exists in database.");
                    return exportGroup;
                }
            }
        }
        _log.info("Export Group does NOT already exist in database.");
        return null;
    }

    /**
     * Get an initiator as specified by the passed initiator data. First checks
     * if an initiator with the specified port already exists in the database,
     * and simply returns that initiator, otherwise creates a new initiator.
     *
     * @param initiatorParam The data for the initiator.
     *
     * @return A reference to an initiator.
     *
     * @throws InternalException When an error occurs querying the database.
     */
    private Initiator getInitiator(Initiator initiatorParam)
        throws InternalException {
        Initiator initiator = null;
        URIQueryResultList resultsList = new URIQueryResultList();
        _dbClient.queryByConstraint(AlternateIdConstraint.Factory.getInitiatorPortInitiatorConstraint(
            initiatorParam.getInitiatorPort()), resultsList);
        Iterator<URI> resultsIter = resultsList.iterator();
        if (resultsIter.hasNext()) {
            initiator = _dbClient.queryObject(Initiator.class, resultsIter.next());
        } else {
            initiatorParam.setId(URIUtil.createId(Initiator.class));
            _dbClient.createObject(initiatorParam);
            initiator = initiatorParam;
        }
        return initiator;
    }

    /* (non-Javadoc)
     * @see com.emc.storageos.volumecontroller.RPController#stopProtection(java.net.URI, java.net.URI, java.lang.String)
     */
    @Override
    public void performProtectionOperation(URI protectionDevice, URI id, URI copyID, String op, String task)
            throws ControllerException {
        RPCGProtectionTaskCompleter taskCompleter = null;
        try {
            ProtectionSystem rpSystem = getRPSystem(protectionDevice);

            taskCompleter = new RPCGProtectionTaskCompleter(id, task);

    		// Lock the CG or fail
    		lockCG(taskCompleter);
    		
    		// set the protection volume to the source volume if the copyID is null (operation is performed on all copies)
    		// otherwise set it to the volume referenced by the copyID (operation is performed on specifc copy)    		    		
    		Volume protectionVolume = (copyID == null) ?  
    				_dbClient.queryObject(Volume.class, id) : _dbClient.queryObject(Volume.class, copyID);
    		
            RecoverPointClient rp = RPHelper.getRecoverPointClient(rpSystem);
            RecoverPointVolumeProtectionInfo volumeProtectionInfo = rp.getProtectionInfoForVolume(protectionVolume.getWWN());
            
            if (op.equals("stop")) {
            	taskCompleter.setOperationTypeEnum(OperationTypeEnum.STOP_RP_LINK);
                rp.disableProtection(volumeProtectionInfo);
                setProtectionSetStatus(volumeProtectionInfo, ProtectionStatus.DISABLED.toString(), rpSystem);
                _log.info("doStopProtection {} - complete", rpSystem.getId());
                taskCompleter.ready(_dbClient, _locker);
            } else if (op.equals("start")) {
            	taskCompleter.setOperationTypeEnum(OperationTypeEnum.START_RP_LINK);
                rp.enableProtection(volumeProtectionInfo);
                setProtectionSetStatus(volumeProtectionInfo, ProtectionStatus.ENABLED.toString(), rpSystem);
                taskCompleter.ready(_dbClient, _locker);
            } else if (op.equals("sync")) {
            	taskCompleter.setOperationTypeEnum(OperationTypeEnum.SYNC_RP_LINK);
                Set<String> volumeWWNs = new HashSet<String>();
                volumeWWNs.add(protectionVolume.getWWN());
                // Create and enable a temporary bookmark for the volume associated with this volume
                CreateBookmarkRequestParams request = new CreateBookmarkRequestParams();
                request.setVolumeWWNSet(volumeWWNs);
                request.setBookmark("Sync-Snapshot");
                CreateBookmarkResponse response = rp.createBookmarks(request);
				if (response == null) {
					taskCompleter.error(_dbClient, _locker,DeviceControllerExceptions.recoverpoint.failedToCreateBookmark());
				} else {
					taskCompleter.ready(_dbClient, _locker);
				}
             } else if (op.equals("pause")) {
              	taskCompleter.setOperationTypeEnum(OperationTypeEnum.PAUSE_RP_LINK);
                rp.pauseTransfer(volumeProtectionInfo);
                setProtectionSetStatus(volumeProtectionInfo, ProtectionStatus.PAUSED.toString(), rpSystem);
                taskCompleter.ready(_dbClient, _locker);
            } else if (op.equals("resume")) {
             	taskCompleter.setOperationTypeEnum(OperationTypeEnum.RESUME_RP_LINK);
                rp.resumeTransfer(volumeProtectionInfo);
                setProtectionSetStatus(volumeProtectionInfo, ProtectionStatus.ENABLED.toString(), rpSystem);
                taskCompleter.ready(_dbClient, _locker);
            } else if (op.equals("failover-test")) {
             	taskCompleter.setOperationTypeEnum(OperationTypeEnum.FAILOVER_TEST_RP_LINK);
                RPCopyRequestParams copyParams = new RPCopyRequestParams();
                copyParams.setCopyVolumeInfo(volumeProtectionInfo);
                rp.failoverCopyTest(copyParams);
                taskCompleter.ready(_dbClient, _locker);
            } else if (op.equals("failover")) {
            	// If the "protectionVolume" is a source personality volume, we're probably dealing with a failover cancel. 
            	if (protectionVolume.getLinkStatus() != null && 
            		protectionVolume.getLinkStatus().equalsIgnoreCase(Volume.LinkStatus.FAILED_OVER.name())) {
            		// TODO: ViPR 2.0 needs to support this.
            		// TODO BEGIN: allow re-failover perform the same as a failback in 2.0 since the UI support will not be there to do a swap or cancel.
            		// Jira CTRL-2773: Once UI adds support for /swap and /failover-cancel, we can remove this and
            		// replace with an error.
            		// If protectionVolume is a source, then the "source" sent in must be a target.  Verify.
                 	taskCompleter.setOperationTypeEnum(OperationTypeEnum.FAILOVER_CANCEL_RP_LINK);
            	    Volume targetVolume = null;
            	    if (protectionVolume.getPersonality() != null &&
            	            protectionVolume.getPersonality().equalsIgnoreCase(Volume.PersonalityTypes.SOURCE.toString())) {
            	        targetVolume = _dbClient.queryObject(Volume.class, id);
            	    } else {
            	        targetVolume = protectionVolume;
            	    }
            		
            		// Disable the image access that is in effect.
            		volumeProtectionInfo = rp.getProtectionInfoForVolume(targetVolume.getWWN());
            		RPCopyRequestParams copyParams = new RPCopyRequestParams();
            		copyParams.setCopyVolumeInfo(volumeProtectionInfo);
            		rp.failoverCopyCancel(copyParams);
            		// Set the flags back to where they belong.
            		updatePostFailoverCancel(targetVolume);
            		taskCompleter.ready(_dbClient, _locker);
            		// TODO END
            		// Replace with this error: taskCompleter.error(_dbClient, _locker, DeviceControllerErrors.recoverpoint.stepFailed("performFailoverOperation: source volume specified for failover where target volume specified is not in failover state"));
            	} else {
            		// Standard failover case.
                 	taskCompleter.setOperationTypeEnum(OperationTypeEnum.FAILOVER_RP_LINK);
            		RPCopyRequestParams copyParams = new RPCopyRequestParams();
            		copyParams.setCopyVolumeInfo(volumeProtectionInfo);
            		rp.failoverCopy(copyParams);
            		updatePostFailover(protectionVolume);
                	taskCompleter.ready(_dbClient, _locker);
            	}
            } else if (op.equals("failover-cancel")) {
            	// If the "protectionVolume" is a source personality volume, we're probably dealing with a failover cancel. 
             	taskCompleter.setOperationTypeEnum(OperationTypeEnum.FAILOVER_CANCEL_RP_LINK);
            	if (protectionVolume.getPersonality().toString().equalsIgnoreCase(Volume.PersonalityTypes.SOURCE.name())) {
            		taskCompleter.error(_dbClient, _locker, DeviceControllerErrors.recoverpoint.stepFailed("performFailoverOperation: source volume specified for failover where target volume specified is not in failover state"));
            	} else {
            		if (protectionVolume.getLinkStatus() != null && 
            			protectionVolume.getLinkStatus().equalsIgnoreCase(Volume.LinkStatus.FAILED_OVER.name())) {
            			// Disable the image access that is in effect.
            			volumeProtectionInfo = rp.getProtectionInfoForVolume(protectionVolume.getWWN());
            			RPCopyRequestParams copyParams = new RPCopyRequestParams();
            			copyParams.setCopyVolumeInfo(volumeProtectionInfo);
            			rp.failoverCopyCancel(copyParams);
            			// Set the flags back to where they belong.
            			updatePostFailoverCancel(protectionVolume);
                    	taskCompleter.ready(_dbClient, _locker);
            		} else {
            			// Illegal condition, you sent down a target volume that's a source where the target is not a failed over target.
                		taskCompleter.error(_dbClient, _locker, DeviceControllerErrors.recoverpoint.stepFailed("performFailoverOperation: source volume specified for failover where target volume specified is not in failover state"));
            		}
            	}
            } else if (op.equals("swap")) {
             	taskCompleter.setOperationTypeEnum(OperationTypeEnum.SWAP_RP_VOLUME);
                RPCopyRequestParams copyParams = new RPCopyRequestParams();
                copyParams.setCopyVolumeInfo(volumeProtectionInfo);
                rp.swapCopy(copyParams);
                updatePostSwapPersonalities(protectionVolume);
                
                // if metropoint:
                //     1. delete the standby CDP copy
                //     2. add back the standby production copy
                //     3. add back the standby CDP copy
                if (_rpHelper.isMetroPointVolume(protectionVolume)) {
                    
                    _log.info(String.format("Adding back standby production copy after swap back to original VPlex Metro for Metropoint volume %s (%s)", 
                            protectionVolume.getLabel(), protectionVolume.getId().toString()));
                    
                    List<Volume> standbyLocalCopyVols = _rpHelper.getMetropointStandbyCopies(protectionVolume);
                    CreateCopyParams standbyLocalCopyParams = new CreateCopyParams();
                    List<CreateRSetParams> rSets = new ArrayList<CreateRSetParams>();
                    Set<URI> journalVolumes = new HashSet<URI>();
                    if (!standbyLocalCopyVols.isEmpty()) {
                        for (Volume standbyCopyVol : standbyLocalCopyVols) {
                            
                            // 1. delete the standby CDP copy if it exists
                            if (rp.doesProtectionVolumeExist(standbyCopyVol.getWWN())) {
                                RecoverPointVolumeProtectionInfo standbyCdpCopy = rp.getProtectionInfoForVolume(standbyCopyVol.getWWN());
                                rp.deleteCopy(standbyCdpCopy);
                            }
                            
                            // set up volume info for the standby copy volume
                            CreateVolumeParams vol = new CreateVolumeParams();
                            vol.setWwn(standbyCopyVol.getWWN());
                            vol.setProduction(false);
                            List<CreateVolumeParams> volumes = new ArrayList<CreateVolumeParams>();
                            volumes.add(vol);
                            CreateRSetParams rSet = new CreateRSetParams();
                            rSet.setName(standbyCopyVol.getRSetName());
                            rSet.setVolumes(volumes);
                            rSets.add(rSet);
                                          
                            // compile a unique set of journal volumes
                            if (standbyCopyVol.getRpJournalVolume() != null) {
                                journalVolumes.add(standbyCopyVol.getRpJournalVolume());
                            }
                        }

                        // prepare journal volumes info
                        String rpCopyName = null;
                        List<CreateVolumeParams> journaVols = new ArrayList<CreateVolumeParams>();
                        for (URI journalVolId : journalVolumes) {
                            Volume standbyLocalJournal = _dbClient.queryObject(Volume.class, journalVolId);
                            if (standbyLocalJournal != null) {
                                _log.info(String.format("Found standby local journal volume %s (%s) for metropoint volume %s (%s)", 
                                        standbyLocalJournal.getLabel(), standbyLocalJournal.getId().toString(),
                                        protectionVolume.getLabel(), protectionVolume.getId().toString()));
                                rpCopyName = standbyLocalJournal.getRpCopyName();
                                CreateVolumeParams journalVolParams = new CreateVolumeParams();
                                journalVolParams.setWwn(standbyLocalJournal.getWWN());
                                journalVolParams.setInternalSiteName(standbyLocalJournal.getInternalSiteName());
                                journaVols.add(journalVolParams);
                            }
                        }
                        
                        // if we found any journal volumes, add them to the local copies list
                        if (!journaVols.isEmpty()) {
                            standbyLocalCopyParams.setName(rpCopyName);
                            standbyLocalCopyParams.setJournals(journaVols);
                        } else {
                            _log.error("no journal volumes found for standby production copy for source volume " + protectionVolume.getLabel());
                        }
                    }
                    
                    Volume standbyProdJournal = _dbClient.queryObject(Volume.class, protectionVolume.getSecondaryRpJournalVolume());
                    
                    if (standbyProdJournal != null) {
                        _log.info(String.format("Found standby production journal volume %s (%s) for metropoint volume %s (%s)", 
                                standbyProdJournal.getLabel(), standbyProdJournal.getId().toString(),
                                protectionVolume.getLabel(), protectionVolume.getId().toString()));
                        List<CreateVolumeParams> journaVols = new ArrayList<CreateVolumeParams>();
                        CreateVolumeParams journalVolParams = new CreateVolumeParams();
                        journalVolParams.setWwn(standbyProdJournal.getWWN());
                        journalVolParams.setInternalSiteName(standbyProdJournal.getInternalSiteName());
                        journaVols.add(journalVolParams);

                        CreateCopyParams standbyProdCopyParams = new CreateCopyParams();
                        standbyProdCopyParams.setName(standbyProdJournal.getRpCopyName());
                        standbyProdCopyParams.setJournals(journaVols);

                        // 2. and 3. add back the standby production copy; add back the standby CDP copy                       
                        rp.addStandbyProductionCopy(standbyProdCopyParams, standbyLocalCopyParams, rSets, copyParams);
                    }                        
                }
                taskCompleter.ready(_dbClient, _locker);
            } else if (op.equals("failover-test-cancel")) {
             	taskCompleter.setOperationTypeEnum(OperationTypeEnum.FAILOVER_TEST_CANCEL_RP_LINK);
                RPCopyRequestParams copyParams = new RPCopyRequestParams();
                copyParams.setCopyVolumeInfo(volumeProtectionInfo);
                rp.failoverCopyTestCancel(copyParams);
                taskCompleter.ready(_dbClient, _locker);
            } else {
                taskCompleter.error(_dbClient, _locker, DeviceControllerErrors.recoverpoint.methodNotSupported());
            }
            _log.info("performProtectionOperation: after " + op + " operation successful");
		} catch (InternalException e) {
        	_log.error("Operation failed with Exception: " , e);
            if (taskCompleter != null)
                taskCompleter.error(_dbClient, _locker, e);
        } catch (Exception e) {
        	_log.error("Operation failed with Exception: " , e);
            if (taskCompleter != null)
                taskCompleter.error(_dbClient, _locker, DeviceControllerException.errors.jobFailed(e));
        }
    }

    /**
     * After a swap, we need to swap personalities of source and target volumes
     *
     * @param id volume we failed over to
     * @throws InternalException
     */
    private void updatePostSwapPersonalities(Volume volume)
            throws InternalException {
        _log.info("Changing personality of source and targets");
        ProtectionSet protectionSet = _dbClient.queryObject(ProtectionSet.class, volume.getProtectionSet());
        List<URI> volumeIDs = new ArrayList<URI>();
        for (String volumeString : protectionSet.getVolumes()) {
            URI volumeURI;
            try {
                volumeURI = new URI(volumeString);
                volumeIDs.add(volumeURI);
            } catch (URISyntaxException e) {
                _log.error("URI syntax incorrect: ", e);
            }
        }
        
        // Changing personalities means that the source was on "Copy Name A" and it's now on "Copy Name B":
        // 1. a. Any previous TARGET volume that matches the copy name of the incoming volume is now a SOURCE volume
        //    b. That voume needs its RP Targets volumes list filled-in as well; it's all of the devices that are 
        //       the same replication set name that aren't the new SOURCE volume itself.
        // 2. All SOURCE volumes are now TARGET volumes and their RP Target lists need to be null'd out
        //
        for (URI protectionVolumeID : volumeIDs) {
            Volume protectionVolume = _dbClient.queryObject(Volume.class, protectionVolumeID);
            if ((protectionVolume.getPersonality().equals(Volume.PersonalityTypes.TARGET.toString())) &&
                (protectionVolume.getRpCopyName().equals(volume.getRpCopyName()))) {
                // This is a TARGET we failed over to.  We need to build up all of its targets
                for (URI potentialTargetVolumeID : volumeIDs) {
                    Volume potentialTargetVolume = _dbClient.queryObject(Volume.class, potentialTargetVolumeID);
                    if (potentialTargetVolume.getRSetName()!=null && 
                    	potentialTargetVolume.getRSetName().equals(protectionVolume.getRSetName()) && 
                    	!potentialTargetVolumeID.equals(protectionVolume.getId())) {
                    	if (protectionVolume.getRpTargets() == null) {
                    		protectionVolume.setRpTargets(new StringSet());
                    	}
                    	protectionVolume.getRpTargets().add(String.valueOf(potentialTargetVolume.getId()));
                    }
                }
                
                _log.info("Change personality of failover target " + protectionVolume.getWWN() + " to source");
                protectionVolume.setPersonality(Volume.PersonalityTypes.SOURCE.toString());
                protectionVolume.setAccessState(Volume.VolumeAccessState.READWRITE.name());
                protectionVolume.setLinkStatus(Volume.LinkStatus.IN_SYNC.name());
                _dbClient.persistObject(protectionVolume);
            } else if (protectionVolume.getPersonality().equals(Volume.PersonalityTypes.SOURCE.toString())) {
                _log.info("Change personality of source volume " + protectionVolume.getWWN() + " to target");
                protectionVolume.setPersonality(Volume.PersonalityTypes.TARGET.toString());
                protectionVolume.setAccessState(Volume.VolumeAccessState.NOT_READY.name());
                protectionVolume.setLinkStatus(Volume.LinkStatus.IN_SYNC.name());
                protectionVolume.setRpTargets(null);
                _dbClient.persistObject(protectionVolume);
            } else if (!protectionVolume.getPersonality().equals(Volume.PersonalityTypes.METADATA.toString())) {
                _log.info("Target " + protectionVolume.getWWN() + " is a target that remains a target");
                // TODO: Handle failover to CRR.  Need to remove the CDP volumes (including journals)
            }
        }
    }

    /**
     * After a failover, we need to set specific flags
     *
     * @param id volume we failed over to
     * @throws InternalException
     */
    private void updatePostFailover(Volume volume) throws InternalException {
        _log.info("Setting respective flags after failover");
        ProtectionSet protectionSet = _dbClient.queryObject(ProtectionSet.class, volume.getProtectionSet());
        List<URI> volumeIDs = new ArrayList<URI>();
        for (String volumeString : protectionSet.getVolumes()) {
            URI volumeURI;
            try {
                volumeURI = new URI(volumeString);
                volumeIDs.add(volumeURI);
            } catch (URISyntaxException e) {
                _log.error("URI syntax incorrect: ", e);
            }
        }
        
        for (URI protectionVolumeID : volumeIDs) {
            Volume protectionVolume = _dbClient.queryObject(Volume.class, protectionVolumeID);
            if ((protectionVolume.getPersonality().equals(Volume.PersonalityTypes.TARGET.toString())) &&
                (protectionVolume.getRpCopyName().equals(volume.getRpCopyName()))) {
                _log.info("Change flags of failover target " + protectionVolume.getWWN());
                protectionVolume.setAccessState(Volume.VolumeAccessState.READWRITE.name());
                protectionVolume.setLinkStatus(Volume.LinkStatus.FAILED_OVER.name());
                _dbClient.persistObject(protectionVolume);
            } else if (protectionVolume.getPersonality().equals(Volume.PersonalityTypes.SOURCE.toString())) {
                _log.info("Change flags of failover source " + protectionVolume.getWWN());
                protectionVolume.setLinkStatus(Volume.LinkStatus.FAILED_OVER.name());
                _dbClient.persistObject(protectionVolume);
            }
        }
    }

    /**
     * After a failover of a failover (without swap), we need to set specific flags
     *
     * @param id volume we failed over to
     * @throws InternalException
     */
    private void updatePostFailoverCancel(Volume volume) throws InternalException {
        _log.info("Setting respective flags after failover of failover");
        ProtectionSet protectionSet = _dbClient.queryObject(ProtectionSet.class, volume.getProtectionSet());
        List<URI> volumeIDs = new ArrayList<URI>();
        for (String volumeString : protectionSet.getVolumes()) {
            URI volumeURI;
            try {
                volumeURI = new URI(volumeString);
                volumeIDs.add(volumeURI);
            } catch (URISyntaxException e) {
                _log.error("URI syntax incorrect: ", e);
            }
        }
        
        for (URI protectionVolumeID : volumeIDs) {
            Volume protectionVolume = _dbClient.queryObject(Volume.class, protectionVolumeID);
            if ((protectionVolume.getPersonality().equals(Volume.PersonalityTypes.TARGET.toString())) &&
                (protectionVolume.getRpCopyName().equals(volume.getRpCopyName()))) {
                _log.info("Change flags of failover target " + protectionVolume.getWWN());
                protectionVolume.setAccessState(Volume.VolumeAccessState.NOT_READY.name());
                protectionVolume.setLinkStatus(Volume.LinkStatus.IN_SYNC.name());
                _dbClient.persistObject(protectionVolume);
            } else if (protectionVolume.getPersonality().equals(Volume.PersonalityTypes.SOURCE.toString())) {
                _log.info("Change flags of failover source " + protectionVolume.getWWN());
                protectionVolume.setLinkStatus(Volume.LinkStatus.IN_SYNC.name());
                _dbClient.persistObject(protectionVolume);
            } 
        }
    }

    @Override
    public void discover(AsyncTask[] tasks) throws InternalException {
        throw DeviceControllerException.exceptions.operationNotSupported();
    }

    /* (non-Javadoc)
     * @see com.emc.storageos.protectioncontroller.RPController#createSnapshot(java.net.URI, java.net.URI, java.util.List, java.lang.Boolean, java.lang.String)
     */
    @Override
    public void createSnapshot(URI protectionDevice, URI storageURI, List<URI> snapshotList, 
    								   Boolean createInactive, String opId) throws InternalException {    	
    	TaskCompleter completer = new BlockSnapshotCreateCompleter(snapshotList, opId);    	
    	Map<URI, Integer> snapshotMap = new HashMap<URI, Integer>();
    	try {    		
    		ProtectionSystem system = null;
            system = _dbClient.queryObject(ProtectionSystem.class, protectionDevice);
	        // Verify non-null storage device returned from the database client.
	        if (system == null) {
	            throw DeviceControllerExceptions.recoverpoint.failedConnectingForMonitoring(protectionDevice);
	        }
	        
	        // A temporary date/time stamp
            String snapshotName = VIPR_SNAPSHOT_PREFIX + new SimpleDateFormat("yyMMdd-HHmmss").format(new java.util.Date());

            Set<String> volumeWWNs = new HashSet<String>();
            boolean rpBookmarkOnly = false;
            for (URI snapshotID : snapshotList) {
            	// create a snapshot map, a map is required to re-use the existing enable image access method. 
            	// using a lun number of -1 for all snaps, this value is not used, hence ok to use that value.
            	snapshotMap.put(snapshotID, ExportGroup.LUN_UNASSIGNED);            	
                // Get the volume associated with this snapshot
                BlockSnapshot snapshot = _dbClient.queryObject(BlockSnapshot.class, snapshotID);
                if (snapshot.getEmName()!=null) {
                    rpBookmarkOnly = true;
                    snapshotName = snapshot.getEmName();
                }
                                               
                Volume volume = _dbClient.queryObject(Volume.class, snapshot.getParent().getURI());
                // Snapshot object's parent volume is the underlying block volume for VPLEX volumes.
                // Retreive the VPLEX volume if the "volume" object is part of VPLEX volume. 
                // if not, then the "volume" object is a regular VMAX/VNX block volume that is RP protected.
              if (Volume.checkForVplexBackEndVolume(_dbClient, volume)) {
            	  volumeWWNs.add(Volume.fetchVplexVolume(_dbClient, volume).getWWN());            	
              } else {
                	volumeWWNs.add(volume.getWWN());
              }              
              
              // Create a new token/taskid and use that in the workflow. 
              // Multiple threads entering this method might collide with each others workflows in cassandra if the taskid is not unique.
              String newToken = UUID.randomUUID().toString();
              // Set up workflow steps.
              Workflow workflow = _workflowService.getNewWorkflow(this, "createSnapshot", true, newToken);                         
              
              // Step 1 - Create a RP bookmark
              String waitFor = addCreateBookmarkStep(workflow, snapshotList, system, snapshotName, volumeWWNs, rpBookmarkOnly);
                         
              if (!rpBookmarkOnly) {             	  
            	  // Local array snap, additional steps required for snap operation
            	  
            	  // Step 2 - Enable image access               
                  waitFor = addEnableImageAccessStep(workflow, system, snapshotMap, waitFor);
                  
                  // Step 3 - Invoke block storage doCreateSnapshot
                  waitFor = addCreateBlockSnapshotStep(workflow, waitFor, storageURI, snapshotList, createInactive, system);
                  
                  // Step 4 - Disable image access
                  addBlockSnapshotDisableImageAccessStep(workflow, waitFor, snapshotList, system);
              } else {
            	  _log.info("RP Bookmark only requested...");
              }                         
                            
              String successMessage = String.format(
                  	"Successfully created snapshot for %s", Joiner.on(",").join(snapshotList)
                  	);
              workflow.executePlan(completer, successMessage);
            }    		
    	} catch (InternalException e) {
        	_log.error("Operation failed with Exception: " , e);        	
            if (completer != null)
                completer.error(_dbClient, e);            
        } catch (Exception e) {
        	_log.error("Operation failed with Exception: " , e);        
            if (completer != null)
                completer.error(_dbClient, DeviceControllerException.errors.jobFailed(e));
        }     
    }


	/**
	 * Add WF step for creating block snapshots
	 * @param workflow Workflow
	 * @param waitFor wait on this step/step-group to finish before invoking the step herein
	 * @param storageURI UID of the storage system
	 * @param snapshotList List of snaphots in the request
	 * @param createInactive Specifies whether the snapshot is created and activated or just created
	 * @param rpSystem Protection system
	 * @return This method step, so the caller can wait on this for invoking subsequent step(s).
	 */
	private String addCreateBlockSnapshotStep(Workflow workflow, String waitFor, URI storageURI,
			List<URI> snapshotList, Boolean createInactive, ProtectionSystem rpSystem) throws InternalException {
		
		String stepId = workflow.createStepId();
		// Now add the steps to create the block snapshot on the storage system
		StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storageURI);				
		Workflow.Method createBlockSnapshotMethod = new Workflow.Method(METHOD_CREATE_BLOCK_SNAPSHOT_STEP, storageURI, snapshotList, createInactive);			
		Workflow.Method rollbackCreateBlockSnapshotMethod = new Workflow.Method(METHOD_ROLLBACK_CREATE_BLOCK_SNAPSHOT);	

       workflow.createStep(STEP_CREATE_BLOCK_SNAPSHOT, "Create Block Snapshot subtask for RP: ",
       		waitFor, storageSystem.getId(), storageSystem.getSystemType(), this.getClass(),
       		createBlockSnapshotMethod, rollbackCreateBlockSnapshotMethod, stepId); 
       _log.info(
       		String.format("Added createBlockSnapshot step [%s] in workflow", stepId));
       
       return STEP_CREATE_BLOCK_SNAPSHOT;      
	}
	
	/**
	 * Invokes the storage specific BlockController method to perform the snapshot operation
	 * @param storageURI Storage System URI
	 * @param snapshotList List of snaps in the request
	 * @param createInactive Specifies whether the snapshot is created and activated or just created
	 * @param stepId workflow step Id for this step.
	 * @return true if successful, false otherwise	 
	 */
	public boolean createBlockSnapshotStep(URI storageURI,
			List<URI> snapshotList, Boolean createInactive, String stepId) {	
		WorkflowStepCompleter.stepExecuting(stepId);
		try {
			StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storageURI);							
			BlockController controller = getController(BlockController.class, storageSystem.getSystemType());
			controller.createSnapshot(storageURI, snapshotList, createInactive, stepId);
		} catch (Exception e) {
			WorkflowStepCompleter.stepFailed(stepId, DeviceControllerException.errors.jobFailed(e));
			return false;
		}	
		return true;
	}
	
	/** Rollback method for Block snapshot create.
	 * 
	 * @param stepId
	 * @return
	 */
	public boolean createBlockSnapshotRollbackStep(String stepId) {	
		WorkflowStepCompleter.stepExecuting(stepId);  
		_log.info(String.format("rollbackCreateBlockSnapshotStep : Nothing to rollback for step id [%s]", stepId));
		WorkflowStepCompleter.stepSucceded(stepId);  
		return true;
	}
     
    /**
     * Add workflow step for creating bookmarks.
     * @param workflow Workflow
     * @param snapshotList List of snapshots
     * @param system Protection System 
     * @param name Snapshot name
     * @param volumeWWNs WWNs of the volumes whose snap is requested
     * @param emOnly if true, an RP bookmark is taken or a local array snap is performed.
     * @return
     */
    public String addCreateBookmarkStep(Workflow workflow, List<URI> snapshotList,
			ProtectionSystem system, String name, Set<String> volumeWWNs,
			boolean emOnly) throws InternalException {
    	
    	String stepId = workflow.createStepId();
        Workflow.Method createBookmarkMethod = new Workflow.Method(METHOD_CREATE_BOOKMARK_STEP,	snapshotList, 
        		system, name, volumeWWNs, emOnly);
        
        Workflow.Method rollbackCreateBookmarkMethod = new Workflow.Method(METHOD_ROLLBACK_CREATE_BOOKMARK_STEP);

        workflow.createStep(STEP_BOOKMARK_CREATE, "Create bookmark subtask for RP: " + name,
        		null, system.getId(), system.getSystemType(), this.getClass(),
        		createBookmarkMethod, rollbackCreateBookmarkMethod, stepId);    
        
        _log.info(
        		String.format("Added create bookmark step [%s] in workflow", stepId));
        
        return STEP_BOOKMARK_CREATE;
    }
    
	/**
	 * This method creates a RP bookmark
	 * @param snapshotList List of snapshot
	 * @param system Protection Sytem
	 * @param snapshotName snapshot name
	 * @param volumeWWNs WWNs of the volumes whose snap is requested
     * @param emOnly if true, an RP bookmark is taken or a local array snap is performed.
	 * @param token step Id corresponding to this step.
	 * @return true if successful, false otherwise.
	 */
	public boolean createBookmarkStep(List<URI> snapshotList,
			ProtectionSystem system, String snapshotName, Set<String> volumeWWNs,
			boolean emOnly, String token) {
		RecoverPointClient rp = RPHelper.getRecoverPointClient(system);
		CreateBookmarkRequestParams request = new CreateBookmarkRequestParams();
		request.setVolumeWWNSet(volumeWWNs);
		request.setBookmark(snapshotName);
		try {
			CreateBookmarkResponse response = rp.createBookmarks(request);
	
			if (response == null) {
			    throw DeviceControllerExceptions.recoverpoint.failedToCreateBookmark();
			}
	
			// RP Bookmark-only flow.
			if (emOnly) {
			    // This will update the blocksnapshot object based on the return of the EM call
				// The construct method will set the task completer on each snapshot 		
			    constructSnapshotObjectFromBookmark(response, system, snapshotList, snapshotName, token);		    
			} else {
				//Update the snapshot object with the snapshotName, this field is required during enable and disable image access later on.
				for (URI snapshotURI : snapshotList) {
					BlockSnapshot snapshot = _dbClient.queryObject(BlockSnapshot.class, snapshotURI);
					snapshot.setEmName(snapshotName);
					_dbClient.persistObject(snapshot);
				}
			}
			 WorkflowStepCompleter.stepSucceded(token);
		} catch (RecoverPointException e) {
			WorkflowStepCompleter.stepFailed(token, e);
			return false;
		} catch (Exception e) {
			WorkflowStepCompleter.stepFailed(token,DeviceControllerException.errors.jobFailed(e));
			return false;
		}
		
		return true;
	}
	
	
	/**
	 * Rollback method for create bookmark step. 
	 * Currently, this is just a dummy step and does nothing.
	 * @param stepId
	 * @return
	 */
	public boolean createBookmarkRollbackStep(String stepId) {	
		WorkflowStepCompleter.stepExecuting(stepId);  
		_log.info(String.format("rollbackCreateBookmarkStep - Nothing to rollback for step id [%s], return", stepId));
		WorkflowStepCompleter.stepSucceded(stepId);
		return true;
	}

    /**
     * Amend the BlockSnapshot object based on the results of the Bookmark creation operation
     *
     * @param result result from the snapshot creation command
     * @param system protection system 
     * @param snapshotList snapshot list generated
     * @param name emName
     * @param opId operation ID for task completer
     * @throws InternalException 
     * @throws FunctionalAPIInternalError_Exception 
     * @throws FunctionalAPIActionFailedException_Exception 
     */
    private void constructSnapshotObjectFromBookmark(CreateBookmarkResponse response, ProtectionSystem system,
            List<URI> snapshotList, String name, String opId) throws InternalException {

        ProtectionSet protectionSet = null;
        RecoverPointClient rp = RPHelper.getRecoverPointClient(system);

        // Update each snapshot object with the respective information.
        for (URI snapshotID : snapshotList) {
            // Get the snapshot and the associated volume
            BlockSnapshot snapshot = _dbClient.queryObject(BlockSnapshot.class, snapshotID);
            Volume volume = _dbClient.queryObject(Volume.class, snapshot.getParent().getURI());
            
            //For RP+VPLEX volumes, we need to fetch the VPLEX volume. 
            //The snapshot objects references the block/back-end volume as its parent. 
            //Fetch the VPLEX volume that is created with this volume as the back-end volume.            
            if (Volume.checkForVplexBackEndVolume(_dbClient, volume)) {
          	  	volume = Volume.fetchVplexVolume(_dbClient, volume);
            }
        
            if (protectionSet==null || !protectionSet.getId().equals(volume.getProtectionSet())) {
                protectionSet = _dbClient.queryObject(ProtectionSet.class, volume.getProtectionSet());
            }
            
            // Gather the bookmark date, which is different than the snapshot date
            Date bookmarkDate = new Date();
            if (response.getVolumeWWNBookmarkDateMap()!=null) {
                bookmarkDate = response.getVolumeWWNBookmarkDateMap().get(volume.getWWN());
            } else {
                _log.warn("Bookmark date was not filled-in.  Will use current date/time.");
            }

            snapshot.setEmName(name);
            
            snapshot.setInactive(false);
            snapshot.setEmBookmarkTime(""+bookmarkDate.getTime());
            snapshot.setCreationTime(Calendar.getInstance());
            snapshot.setTechnologyType(TechnologyType.RP.toString());

            Volume targetVolume = RPHelper.getRPTargetVolumeFromSource(_dbClient, volume, snapshot.getVirtualArray());
            
            // This section will identify and store the COPY ID associated with the bookmarks created.
            // It is critical to store this information so we can later determine which bookmarks have
            // been deleted from the RPA.
            //
            // May be able to remove this if the protection set object is more detailed (for instance, if
            // we store the copy id with the volume)                      
            RecoverPointVolumeProtectionInfo protectionInfo = rp.getProtectionInfoForVolume(targetVolume.getWWN());
            for (RPConsistencyGroup rpcg : response.getCgBookmarkMap().keySet()) {
                if (rpcg.getCGUID().getId() == protectionInfo.getRpVolumeGroupID()) {
                    for (RPBookmark bookmark : response.getCgBookmarkMap().get(rpcg)) {
                        if (bookmark.getBookmarkName() != null && bookmark.getBookmarkName().equalsIgnoreCase(name) &&
                            bookmark.getCGGroupCopyUID().getGlobalCopyUID().getCopyUID() == protectionInfo.getRpVolumeGroupCopyID()) {
                            snapshot.setEmCGGroupCopyId(protectionInfo.getRpVolumeGroupCopyID());
                            break;
                        }
                    }
                }        
            }
            
            if (targetVolume.getId().equals(volume.getId())) {
            	_log.error("The source and the target volumes are the same");
            	throw DeviceControllerExceptions.recoverpoint.cannotActivateSnapshotNoTargetVolume();
            }
            
            snapshot.setStorageController(targetVolume.getStorageController());
            snapshot.setVirtualArray(targetVolume.getVirtualArray());   
            snapshot.setNativeId(targetVolume.getNativeId());
            snapshot.setAlternateName(targetVolume.getAlternateName());
            snapshot.setNativeGuid(NativeGUIDGenerator.generateNativeGuid(system, snapshot));
            snapshot.setIsSyncActive(false);

            // Setting the WWN of the bookmark to the WWN of the volume, no functional reason for now.
            snapshot.setWWN(targetVolume.getWWN());
            snapshot.setProtectionController(system.getId());
            snapshot.setProtectionSet(volume.getProtectionSet().getURI());

            _log.info(String.format("Updated bookmark %1$s associated with block volume %2$s on site %3$s.", name, volume.getDeviceLabel(), snapshot.getEmInternalSiteName()));
            _dbClient.persistObject(snapshot);

            List<URI> taskSnapshotURIList = new ArrayList<URI>();
            taskSnapshotURIList.add(snapshot.getId());
            TaskCompleter completer = new BlockSnapshotCreateCompleter(taskSnapshotURIList, opId);
            completer.ready(_dbClient);
        }
        // Get information about the bookmarks created so we can get to them later.
        _log.info("Bookmark(s) created for snapshot operation");
        return;
    }

    @Override
    public void restoreVolume(URI protectionDevice, URI storageDevice, URI snapshotID, String opId) throws InternalException {
        TaskLockingCompleter completer = null;
        try {
            _log.info("Restoring  bookmark on the RP CG");

            ProtectionSystem system = null;
            system = _dbClient.queryObject(ProtectionSystem.class, protectionDevice);
            if (system == null) {
            	// Verify non-null storage device returned from the database client.
                throw DeviceControllerExceptions.recoverpoint.failedConnectingForMonitoring(protectionDevice);
            }

            Set<String> volumeWWNs = new HashSet<String>();
            String emName = null;

            // Get the volume associated with this snapshot
            BlockSnapshot snapshot = _dbClient.queryObject(BlockSnapshot.class, snapshotID);
            if (snapshot.getEmName()!=null) {
                emName = snapshot.getEmName();
            }

    		completer = new BlockSnapshotRestoreCompleter(snapshot, opId);
            Volume volume = _dbClient.queryObject(Volume.class, snapshot.getParent().getURI());

            // Lock the CG or fail
    		lockCG(completer);

            // Now determine the target volume that corresponds to the site of the snapshot
            ProtectionSet protectionSet = _dbClient.queryObject(ProtectionSet.class, volume.getProtectionSet());
            Volume targetVolume = ProtectionSet.getTargetVolumeFromSourceAndInternalSiteName(_dbClient, protectionSet, volume, snapshot.getEmInternalSiteName());

            volumeWWNs.add(targetVolume.getWWN());

            // Now restore image access
            RecoverPointClient rp = RPHelper.getRecoverPointClient(system);
            MultiCopyRestoreImageRequestParams request = new MultiCopyRestoreImageRequestParams();
            request.setBookmark(emName);
            request.setVolumeWWNSet(volumeWWNs);
            MultiCopyRestoreImageResponse response = rp.restoreImageCopies(request);

            if (response == null) {
                throw DeviceControllerExceptions.recoverpoint.failedToImageAccessBookmark();
            }

            completer.ready(_dbClient, _locker);

        } catch (InternalException e) {
        	_log.error("Operation failed with Exception: " , e);
            if (completer != null)
                completer.error(_dbClient, _locker, e);
        } catch (URISyntaxException e) {
        	_log.error("Operation failed with Exception: " , e);
            if (completer != null)
                completer.error(_dbClient, _locker, DeviceControllerException.errors.invalidURI(e));
        } catch (Exception e) {
        	_log.error("Operation failed with Exception: " , e);
            if (completer != null)
                completer.error(_dbClient, _locker, DeviceControllerException.errors.jobFailed(e));
        } 
    }

    /**
     * Enable image access for RP snapshots.
     * 
     * @param protectionDevice protection system
     * @param storageDevice storage device of the backing (parent) volume
     * @param snapshotList list of snapshots to enable
     * @param opId task ID
     * @return true if operation was successful
     * @throws ControllerException
     */
    private boolean enableImageForSnapshots(URI protectionDevice, URI storageDevice, List<URI> snapshotList, String opId)
            throws ControllerException {
        TaskCompleter completer = null;
        try {
            _log.info("Activating a bookmark on the RP CG(s)");

            completer = new BlockSnapshotActivateCompleter(snapshotList, opId);

            ProtectionSystem system = null;
            try {
                system = _dbClient.queryObject(ProtectionSystem.class, protectionDevice);
            } catch (DatabaseException e) {
                throw DeviceControllerExceptions.recoverpoint.databaseExceptionActivateSnapshot(protectionDevice);
            }
            
            // Verify non-null storage device returned from the database client.
            if (system == null) {
                throw DeviceControllerExceptions.recoverpoint.databaseExceptionActivateSnapshot(protectionDevice);
            }

            Set<String> volumeWWNs = new HashSet<String>();
            String emName = null;
            for (URI snapshotID : snapshotList) {
                // Get the volume associated with this snapshot
                BlockSnapshot snapshot = _dbClient.queryObject(BlockSnapshot.class, snapshotID);
                if (snapshot.getEmName()!=null) {
                    emName = snapshot.getEmName();
                }
                Volume volume = _dbClient.queryObject(Volume.class, snapshot.getParent().getURI());
                //For RP+VPLEX volumes, we need to fetch the VPLEX volume. 
                //The snapshot objects references the block/back-end volume as its parent. 
                //Fetch the VPLEX volume that is created with this volume as the back-end volume.            
                if (Volume.checkForVplexBackEndVolume(_dbClient, volume)) {
              	  	volume = Volume.fetchVplexVolume(_dbClient, volume);
                }

                // If the volume type is TARGET, then the enable image access request is part of snapshot create, just add the volumeWWN to the list. 
                // If the personality is SOURCE, then the enable image access request is part of export operation.
                if (volume.getPersonality().equals(Volume.PersonalityTypes.TARGET.toString())) {
                	volumeWWNs.add(volume.getWWN());
                } else {
	                // Now determine the target volume that corresponds to the site of the snapshot
	                ProtectionSet protectionSet = _dbClient.queryObject(ProtectionSet.class, volume.getProtectionSet());
	                Volume targetVolume = ProtectionSet.getTargetVolumeFromSourceAndInternalSiteName(_dbClient, protectionSet, volume, snapshot.getEmInternalSiteName());
	                volumeWWNs.add(targetVolume.getWWN());
                }
            }

            // Now enable image access to that bookmark
            RecoverPointClient rp = RPHelper.getRecoverPointClient(system);
            MultiCopyEnableImageRequestParams request = new MultiCopyEnableImageRequestParams();
            request.setVolumeWWNSet(volumeWWNs);
            request.setBookmark(emName);
            MultiCopyEnableImageResponse response = rp.enableImageCopies(request);

            if (response == null) {
                throw DeviceControllerExceptions.recoverpoint.failedEnableAccessOnRP();
            }

            // Mark the snapshots
            StringSet snapshots = new StringSet();
            for (URI snapshotID : snapshotList) {
                BlockSnapshot snapshot = _dbClient.queryObject(BlockSnapshot.class, snapshotID);
                snapshot.setInactive(false);
                snapshot.setIsSyncActive(true);
                snapshots.add(snapshot.getNativeId());
                _dbClient.persistObject(snapshot);
            }

            completer.ready(_dbClient);
            return true;

        } catch (InternalException e) {
        	_log.error("Operation failed with Exception: " , e);
            if (completer != null)
                completer.error(_dbClient, e);
            return false;
        } catch (URISyntaxException e) {
        	_log.error("Operation failed with Exception: " , e);
            if (completer != null)
                completer.error(_dbClient, DeviceControllerException.errors.invalidURI(e));
            return false;
		} catch (Exception e) {
        	_log.error("Operation failed with Exception: " , e);
            if (completer != null)
                completer.error(_dbClient, DeviceControllerException.errors.jobFailed(e));
            return false;
        } 
    }

    /**
     * Disable image access for RP snapshots.
     * 
     * @param protectionDevice protection system
     * @param snapshotList list of snapshots to enable
     * @param isRollback true if method is invoked as part of a rollback, false otherwise.
     * @param opId
     * @throws ControllerException
     */
    private void disableImageForSnapshots(URI protectionDevice, List<URI> snapshotList, boolean isRollback, String opId) 
    				throws ControllerException {
        TaskCompleter completer = null;
        try {
            _log.info("Deactivating a bookmark on the RP CG(s)");

            completer = new BlockSnapshotDeactivateCompleter(snapshotList, opId);

            ProtectionSystem system = null;
            try {
                system = _dbClient.queryObject(ProtectionSystem.class, protectionDevice);
            } catch (DatabaseException e) {
                throw DeviceControllerExceptions.recoverpoint.databaseExceptionDeactivateSnapshot(protectionDevice);
            }

            if (system == null) {
                throw DeviceControllerExceptions.recoverpoint.databaseExceptionDeactivateSnapshot(protectionDevice);
            }

            Set<String> volumeWWNs = new HashSet<String>();
            String emName = "";
            for (URI snapshotID : snapshotList) {
                // Get the volume associated with this snapshot
                BlockSnapshot snapshot = _dbClient.queryObject(BlockSnapshot.class, snapshotID);
                if (snapshot.getEmName()!=null) {
                    emName = snapshot.getEmName();
                }
                Volume volume = _dbClient.queryObject(Volume.class, snapshot.getParent().getURI());
                
                //For RP+VPLEX volumes, we need to fetch the VPLEX volume. 
                //The snapshot objects references the block/back-end volume as its parent. 
                //Fetch the VPLEX volume that is created with this volume as the back-end volume.            
                if (Volume.checkForVplexBackEndVolume(_dbClient, volume)) {
              	  	volume = Volume.fetchVplexVolume(_dbClient, volume);
                }

                // If the volume type is TARGET, then the enable image access request is part of snapshot create, just add the volumeWWN to the list. 
                // If the personality is SOURCE, then the enable image access request is part of export operation.
                if (volume.getPersonality().equals(Volume.PersonalityTypes.TARGET.toString())) {
                	volumeWWNs.add(volume.getWWN());
                } else {
	                // Now determine the target volume that corresponds to the site of the snapshot
	                ProtectionSet protectionSet = _dbClient.queryObject(ProtectionSet.class, volume.getProtectionSet());
	                Volume targetVolume = ProtectionSet.getTargetVolumeFromSourceAndInternalSiteName(_dbClient, protectionSet, volume, snapshot.getEmInternalSiteName());
	
	                volumeWWNs.add(targetVolume.getWWN());
                }
            }

            // Now disable image access to that bookmark
            RecoverPointClient rp = RPHelper.getRecoverPointClient(system);
            MultiCopyDisableImageRequestParams request = new MultiCopyDisableImageRequestParams();
            request.setVolumeWWNSet(volumeWWNs);
            request.setEmName(emName);
            MultiCopyDisableImageResponse response = rp.disableImageCopies(request);

            if (response == null) {
                throw DeviceControllerExceptions.recoverpoint.failedDisableAccessOnRP();
            }

            // Mark the snapshots
            StringSet snapshots = new StringSet();
            for (URI snapshotID : snapshotList) {
                BlockSnapshot snapshot = _dbClient.queryObject(BlockSnapshot.class, snapshotID);
                snapshot.setInactive(isRollback ? true : false);
                snapshot.setIsSyncActive(false);
                snapshots.add(snapshot.getNativeId());
                _dbClient.persistObject(snapshot);
            }

            completer.ready(_dbClient);
        } catch (InternalException e) {
        	_log.error("Operation failed with Exception: " , e);
            if (completer != null)
                completer.error(_dbClient, e);
        } catch (URISyntaxException e) {
        	_log.error("Operation failed with Exception: " , e);
            if (completer != null)
                completer.error(_dbClient, DeviceControllerException.errors.invalidURI(e));
		} catch (Exception e) {
        	_log.error("Operation failed with Exception: " , e);
            if (completer != null) 
                completer.error(_dbClient, DeviceControllerException.errors.jobFailed(e));
        } 
    }

    @Override
    public void deleteSnapshot(URI protectionDevice, URI snapshotURI, String opId) throws InternalException {
        TaskCompleter taskCompleter = null;
        try {
            BlockSnapshot snap = _dbClient.queryObject(BlockSnapshot.class, snapshotURI);
            taskCompleter = BlockSnapshotDeleteCompleter.createCompleter(_dbClient, snap, opId);
            
            List<BlockSnapshot> snapshots = new ArrayList<BlockSnapshot>();
            
            URI cgId = null;
            if (snap.getConsistencyGroup() != null) {
                cgId = snap.getConsistencyGroup();
            }
            
            if (cgId != null) {
                // Account for all CG BlockSnapshots if this requested BlockSnapshot
                // references a CG.
                snapshots = ControllerUtils.getBlockSnapshotsBySnapsetLabelForProject(snap, _dbClient);
            } else {
                snapshots.add(snap);
            }

            for (BlockSnapshot snapshot : snapshots) {
                if (snapshot != null && !snapshot.getInactive()) {
                    snapshot.setInactive(true);
                    snapshot.setIsSyncActive(false);
                    _dbClient.persistObject(snapshot);
                } 
                
                // Perhaps the snap is already deleted/inactive.
                // In that case, we'll just say all is well, so that this operation
                // is idempotent.
            }
            taskCompleter.ready(_dbClient);
        } catch (InternalException e) {
            String message = String.format("Generic exception when trying to delete snapshot %s on protection system %s",
                    String.valueOf(snapshotURI), protectionDevice);
            _log.error(message, e);
            taskCompleter.error(_dbClient, e);
        } catch (Exception e) {
            String message = String.format("Generic exception when trying to delete snapshot %s on protection system %s",
                    String.valueOf(snapshotURI), protectionDevice);
            _log.error(message, e);
    	    ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            taskCompleter.error(_dbClient, serviceError);
        }
    }

    /**
     * Collects the RP statistics for the given <code>ProtectionSystem</code>.
     * @param protectionSystem
     * @throws InternalException
     */
    private void collectRPStatistics(ProtectionSystem protectionSystem) throws InternalException {
        RecoverPointClient rpClient = RPHelper.getRecoverPointClient(protectionSystem);
        Set<RPSite> rpSites = rpClient.getAssociatedRPSites();
        RecoverPointStatisticsResponse response = rpClient.getRPSystemStatistics();
    
        _rpStatsHelper.updateProtectionSystemMetrics(protectionSystem, rpSites, response, _dbClient);
    }
      
    private void setProtectionSetStatus(RecoverPointVolumeProtectionInfo volumeProtectionInfo, String protectionSetStatus, ProtectionSystem system) {
        //
        // If volumeProtectionInfo is the source, then set the protection status of the whole protection set.
        // We don't have the ability to set the status of the individual copies, yet.
        //
        if (volumeProtectionInfo.getRpVolumeCurrentProtectionStatus() == RecoverPointVolumeProtectionInfo.volumeProtectionStatus.PROTECTED_SOURCE) {
            URIQueryResultList list = new URIQueryResultList();
            Constraint constraint = ContainmentConstraint.Factory.getProtectionSystemProtectionSetConstraint(system.getId());
            try {
                _dbClient.queryByConstraint(constraint, list);
                Iterator<URI> it = list.iterator();
                while (it.hasNext()) {
                    URI protectionSetId = it.next();
                    _log.info("Check protection set ID: " + protectionSetId);
                    ProtectionSet protectionSet;
                    protectionSet = _dbClient.queryObject(ProtectionSet.class, protectionSetId);
                    if (protectionSet.getInactive() == false) {
                        _log.info("Change the status to: " + protectionSetStatus);
                        protectionSet.setProtectionStatus(protectionSetStatus);
                        _dbClient.persistObject(protectionSet);
                        break;
                    }
                }
            } catch (DatabaseException e) {
                // Don't worry about this
            }
        } else {
            _log.info("Did not pause the protection source.  Not updating protection status");
        }
    }

    /**
     * Looks up controller dependency for given hardware
     *
     * @param clazz controller interface
     * @param hw hardware name
     * @param <T>
     * @return
     * @throws CoordinatorException
     */
    protected <T extends StorageController> T getController(Class<T> clazz, String hw) throws CoordinatorException {
        return _coordinator.locateService(
                clazz, CONTROLLER_SVC, CONTROLLER_SVC_VER, hw, clazz.getSimpleName());
    }

    /**
     * Check if initiator being added to export-group is good.
     *
     * @param exportGroup
     * @param initiator
     * @throws InternalException
     */
    private URI getInitiatorNetwork(ExportGroup exportGroup, Initiator initiator) throws InternalException {
        _log.info(String.format("Export(%s), Initiator: p(%s), port(%s)",
                exportGroup.getLabel(), initiator.getProtocol(), initiator.getInitiatorPort()));

        NetworkLite net = BlockStorageScheduler.lookupNetworkLite(_dbClient, StorageProtocol.block2Transport(initiator.getProtocol()),
                initiator.getInitiatorPort());

        // If this port is unplugged or in a network we don't know about or in a network that is unregistered, then we can't use it.
        if (net == null || RegistrationStatus.UNREGISTERED.toString().equalsIgnoreCase(net.getRegistrationStatus()) ) {
        	return null;
        }
        
        return net.getId();
    }

    private void initTaskStatus(ExportGroup exportGroup, String task, Operation.Status status, String message) {
        if (exportGroup.getOpStatus() == null) {
            exportGroup.setOpStatus(new OpStatusMap());
        }
        final Operation op = new Operation();
        if (status == Operation.Status.ready) {
            op.ready();
        }
        exportGroup.getOpStatus().put(task, op);
    }

    @Override
    public void exportGroupUpdate(URI storageURI, URI exportGroupURI,
            Workflow storageWorkflow, String token) throws Exception {
    	
        TaskCompleter taskCompleter = null;
        try {
            _log.info(String.format("exportGroupUpdate start - Array: %s ExportMask: %s",
                    storageURI.toString(), exportGroupURI.toString()));
            ExportGroup exportGroup = _dbClient.queryObject(ExportGroup.class, 
            		exportGroupURI);
            ProtectionSystem storage = _dbClient.queryObject(ProtectionSystem.class, 
            		storageURI);            
            taskCompleter = new ExportOrchestrationTask(exportGroupURI, token);
            String successMessage = String.format(
            	"ExportGroup %s successfully updated for StorageArray %s",
            	exportGroup.getLabel(), storage.getLabel());
            storageWorkflow.set_service(_workflowService);
            storageWorkflow.executePlan(taskCompleter, successMessage);
        } catch (InternalException e) {
        	_log.error("Operation failed with Exception: " , e);
            if (taskCompleter != null)
                taskCompleter.error(_dbClient, e);
        } catch (Exception e) {
        	_log.error("Operation failed with Exception: " , e);
            if (taskCompleter != null) {
                taskCompleter.error(_dbClient, DeviceControllerException.errors.jobFailed(e));
            }
        }
    }

    /**
     * Searches for all specified bookmarks (RP snapshots).  If even just one
     * bookmark does not exist, an exception will be thrown.  
     * 
     * @param protectionDevice the protection system URI
     * @param snapshots the RP snapshots to search for
     */
    private void searchForBookmarks(URI protectionDevice, Set<URI> snapshots) {
        ProtectionSystem rpSystem = getRPSystem(protectionDevice);      

        RecoverPointClient rpClient = RPHelper.getRecoverPointClient(rpSystem);
        
        // Check that the bookmarks actually exist
        Set<Integer> cgIDs = null;
        boolean bookmarkExists;
        
        // Map used to keep track of which BlockSnapshots map to which CGs
        Map<Integer, List<BlockSnapshot>> cgSnaps = new HashMap<Integer, List<BlockSnapshot>>();
        
        for (URI snapshotID : snapshots) {
        	cgIDs = new HashSet<Integer>();
        	
            BlockSnapshot snapshot = _dbClient.queryObject(BlockSnapshot.class, snapshotID);
           
            // Get the volume associated with this snapshot
            Volume volume = _dbClient.queryObject(Volume.class, snapshot.getParent().getURI());

            // Now get the protection set (CG) associated with the volume so we can use
            // it to search for the bookmark
            ProtectionSet protectionSet = _dbClient.queryObject(ProtectionSet.class, volume.getProtectionSet());
            
            Integer cgID = null;
            
            try {
            	cgID = Integer.valueOf(protectionSet.getProtectionId());
            } catch (NumberFormatException nfe) {
            	throw DeviceControllerExceptions.recoverpoint.exceptionLookingForBookmarks(nfe);
            }
            
            cgIDs.add(cgID);

            if (cgSnaps.get(cgID) == null) {
            	cgSnaps.put(cgID, new ArrayList<BlockSnapshot>());
            }
            
            cgSnaps.get(cgID).add(snapshot);
        }
        
        GetBookmarksResponse bookmarkResponse = rpClient.getRPBookmarks(cgIDs);
            
        // Iterate over the BlockSnapshots for each CG and determine if each
        // one exists in RP.  Fail if any of the snapshots does not exist.
        for (Integer cgID : cgSnaps.keySet()) {
        	for (BlockSnapshot snapshot : cgSnaps.get(cgID)) {
        		bookmarkExists = false;
        		
        		if (bookmarkResponse.getCgBookmarkMap() != null 
                		&& !bookmarkResponse.getCgBookmarkMap().isEmpty()) {
                	List<RPBookmark> rpBookmarks = bookmarkResponse.getCgBookmarkMap().get(cgID);
                	
                	if (rpBookmarks != null && !rpBookmarks.isEmpty()) {
                		// Find the bookmark
                		for (RPBookmark rpBookmark : rpBookmarks) {
                			if (rpBookmark.getBookmarkName().equals(snapshot.getEmName())) {
                				bookmarkExists = true;
                			}
                		}
                	}
                }
                
                if (!bookmarkExists) {
                	throw DeviceControllerExceptions.recoverpoint.failedToFindExpectedBookmarks();
                }
        	}
        }
     
    }
    
    @Override
    public void exportGroupChangePathParams(URI storageURI, URI exportGroupURI,
            URI volumeURI, String token) throws Exception {
	// Not supported, will not be called because API code not present
    }
    @Override
    public void increaseMaxPaths(Workflow workflow, StorageSystem storageSystem, 
            ExportGroup exportGroup, ExportMask exportMask, List<URI> newInitiators, String token) 
        throws Exception {
	// Not supported, will not be called because API code not present
    }
    
    /**
     * Returns the Storage Ports on the Storage device that should be used for a particular
     * storage array. This is done by finding ports in the array and RP initiators that have
     * common Networks. Returns a map of NetworkURI to List<StoragePort>.
     * 
     * @param rpInitiatorNetworkURI The URI of network where this RP site is in
     * @param arrayURI The URI of a connected backend storage system.
     * @param varrayURI The URI of the virtual array.
     * 
     * @return Map<URI, List<StoragePort>> A map of Network URI to a List<StoragePort>
     */
    private Map<URI, List<StoragePort>> getInitiatorPortsForArray(Map<URI, Set<Initiator>> rpNetworkToInitiatorMap,
        URI arrayURI, URI varray) throws ControllerException {
        
        Map<URI, List<StoragePort>> initiatorMap = new HashMap<URI, List<StoragePort>>();                                

        // Then get the front end ports on the Storage array.
        Map<URI, List<StoragePort>> arrayTargetMap = ConnectivityUtil.getStoragePortsOfType(_dbClient, 
                arrayURI, StoragePort.PortType.frontend);
        
        // Eliminate any storage ports that are not explicitly assigned
        // or implicitly connected to the passed varray.
        Set<URI> arrayTargetNetworks = new HashSet<URI>();
        arrayTargetNetworks.addAll(arrayTargetMap.keySet());
        Iterator<URI> arrayTargetNetworksIter = arrayTargetNetworks.iterator();
        while (arrayTargetNetworksIter.hasNext()) {
            URI networkURI = arrayTargetNetworksIter.next();
            Iterator<StoragePort> targetStoragePortsIter = arrayTargetMap.get(networkURI).iterator();
            while (targetStoragePortsIter.hasNext()) {
                StoragePort targetStoragePort = targetStoragePortsIter.next();
                StringSet taggedVArraysForPort = targetStoragePort.getTaggedVirtualArrays();
                if ((taggedVArraysForPort == null) || (!taggedVArraysForPort.contains(varray.toString()))) {
                    targetStoragePortsIter.remove();
                }
            }
            
            // If the entry for this network is now empty then
            // remove the entry from the target storage port map.
            if (arrayTargetMap.get(networkURI).isEmpty()) {
                arrayTargetMap.remove(networkURI);
            }
        }
        

        //Get all the ports corresponding to the network that the RP initiators are in.
        //we will use all available ports     
        for (URI rpInitiatorNetworkURI : rpNetworkToInitiatorMap.keySet()) {
	        if (arrayTargetMap.keySet().contains(rpInitiatorNetworkURI)) {      
	        	initiatorMap.put(rpInitiatorNetworkURI, arrayTargetMap.get(rpInitiatorNetworkURI));
	        }	      
        }

        // If there are no initiator ports, fail the operation, because we cannot zone.
       if (initiatorMap.isEmpty()) {      
    	   Set<Initiator> rpInitiatorSet = rpNetworkToInitiatorMap.get(rpNetworkToInitiatorMap.keySet().iterator().next());
    	   String rpSiteName = rpInitiatorSet.iterator().next().getHostName();
          throw RecoverPointException.exceptions.getInitiatorPortsForArrayFailed(rpSiteName, 
                  arrayURI.toString()); 
        }

        return initiatorMap;
    }
    
        
    /**
     * Compute the number of paths to use on the back end array.
     * This is done on a per Network basis and then summed together.
     * Within each Network, we determine the number of ports available, and then
     * convert to paths. Currently we don't allocate more paths than initiators.
     * @param initiatorPortMap -- used to determine networks and initiator counts
     * @param varray -- only Networks in the specified varray are considered
     * @param array -- StorageSystem -- used to determine available ports
     * @return
     */
    private Integer computeNumPaths(Map<URI, List<StoragePort>> initiatorPortMap, URI varray, StorageSystem array) {
        // Get the number of ports per path.
        StoragePortsAssigner assigner = StoragePortsAssignerFactory.getAssigner(array.getSystemType());
        int portsPerPath = assigner.getNumberOfPortsPerPath();
        // Get the array's front end ports for this varray only
        Map<URI, List<StoragePort>> arrayTargetMap = ConnectivityUtil.getStoragePortsOfTypeAndVArray(_dbClient,
                array.getId(), StoragePort.PortType.frontend, varray);
        
        int numPaths = 0;
        for (URI networkURI : initiatorPortMap.keySet()) {
            if (arrayTargetMap.get(networkURI) != null) {
                int pathsInNetwork = arrayTargetMap.get(networkURI).size() / portsPerPath;
                int initiatorsInNetwork = initiatorPortMap.get(networkURI).size();
                if (pathsInNetwork > initiatorsInNetwork) pathsInNetwork = initiatorsInNetwork;
                _log.info(String.format("Network %s has %s paths", networkURI, pathsInNetwork));
                numPaths += pathsInNetwork;
            } else {
                _log.info(String.format("Storage Array %s has no ports in Network %s", 
                        array.getNativeGuid(), networkURI));
            }
        }
        return numPaths;
    }

	@Override
	public String addStepsForExpandVolume(Workflow workflow, String waitFor,
			List<VolumeDescriptor> volumeURIs, String taskId) 
					throws InternalException {
		// There are no RP specific operations done during the expand process. 
		// Most of what is required from RP as part of the volume expand is handled in Pre and Post Expand steps.
		return null;
	}

    @Override
    public String addStepsForChangeVirtualArray(Workflow workflow, String waitFor,
        List<VolumeDescriptor> volumes, String taskId) throws InternalException {
        // Nothing to do, no steps to add
        return waitFor;
    }
    
    /**
     * Update the backing volume virtual pool reference, needed for change vpool
     * operations for RP+VPLEX and MetroPoint.
     * 
     * @param volumeDescriptors
     *            The Volume descriptors, needed to see if there are any
     *            migrations present.
     * @param volume
     *            The source volume
     * @param srcVpoolURI
     *            The new vpool
     */
    private void updateVPlexBackingVolumeVpools(Volume volume, URI srcVpoolURI) {
        // Check to see if this is a VPLEX virtual volume
        if (volume.getAssociatedVolumes() != null
                && !volume.getAssociatedVolumes().isEmpty()) {
            
            _log.info("Update the virtual pool on backing volume(s) for virtual volume [{}].", volume.getLabel());                                       
            VirtualPool srcVpool = _dbClient.queryObject(VirtualPool.class, srcVpoolURI);
            String srcVpoolName = srcVpool.getLabel();
            URI haVpoolURI = null;
            String haVpoolName = null;
            
            // We only have to get the HA vpool URI if there are more than 1 associated backing volumes.
            if (volume.getAssociatedVolumes().size() > 1) {                        
                // Find the HA vpool from the source vpool
                VirtualPool haVpool = VirtualPool.getHAVPool(srcVpool, _dbClient);
                
                // If the HA vpool is null, it means the src vpool is the HA vpool
                haVpool = (haVpool == null) ? srcVpool : haVpool;
                
                haVpoolURI = haVpool.getId();
                haVpoolName = haVpool.getLabel();
            }

            // Check each backing volume, if the varray is the same as the virtual volume passed in
            // then the backing volume would have the same 
            for (String associatedVolId : volume.getAssociatedVolumes()) {
                Volume associatedVol = _dbClient.queryObject(Volume.class, URI.create(associatedVolId));
                
                URI vpoolURI = srcVpoolURI;
                String vpoolName = srcVpoolName;
                
                // If the backing volume does not have the same varray as the source virtual
                // volume, then we must be looking at the HA backing volume.
                if (!associatedVol.getVirtualArray().equals(volume.getVirtualArray())) {
                    vpoolURI = haVpoolURI;
                    vpoolName = haVpoolName;
                }
                
                _log.info("Update backing volume [{}] virtual pool to [{}].", associatedVol.getLabel(), vpoolName);
                associatedVol.setVirtualPool(vpoolURI);
                // Update the backing volume
                _dbClient.persistObject(associatedVol);                    
            }
        }
    }

    private void addUpdateCGStep(Workflow workflow,
            List<VolumeDescriptor> volumeDescriptors,
            CGRequestParams cgParams, ProtectionSystem rpSystem, String taskId) throws InternalException {
        String stepId = workflow.createStepId();
        Workflow.Method cgCreationExecuteMethod = new Workflow.Method(METHOD_CG_UPDATE_STEP,
                rpSystem.getId(),
                volumeDescriptors,
                cgParams);
        Workflow.Method cgCreationExecutionRollbackMethod = new Workflow.Method(METHOD_CG_UPDATE_ROLLBACK_STEP,
                rpSystem.getId());

        workflow.createStep(STEP_CG_UPDATE, "Update consistency group subtask for RP CG: " + cgParams.getCgName(),
        		STEP_EXPORT_ORCHESTRATION, rpSystem.getId(), rpSystem.getSystemType(), this.getClass(),
                cgCreationExecuteMethod, cgCreationExecutionRollbackMethod, stepId);        
    }
    
    /**
     * Workflow step method for updating a consistency group.
     *
     * @param rpSystemId RP system Id
     * @param recommendation parameters needed to create the CG
     * @param token the task
     * @return
     * @throws InternalException 
     */
    public boolean cgUpdateStep(URI rpSystemId, List<VolumeDescriptor> volumeDescriptors, CGRequestParams cgParams, String token) throws InternalException {          
        try {            
            // Get only the RP_EXISTING_PROTECTED_SOURCE descriptors
            List<VolumeDescriptor> existingProtectedSourceVolumeDescriptors = VolumeDescriptor.filterByType(volumeDescriptors, 
                    new VolumeDescriptor.Type[] { VolumeDescriptor.Type.RP_EXISTING_PROTECTED_SOURCE }, 
                    new VolumeDescriptor.Type[] { });
            
            WorkflowStepCompleter.stepExecuting(token);
            _log.info("Update CG step executing");
                                    
            ProtectionSystem rpSystem = _dbClient.queryObject(ProtectionSystem.class, rpSystemId);
            
            for (VolumeDescriptor descriptor : existingProtectedSourceVolumeDescriptors) {
                Volume sourceVolume = _dbClient.queryObject(Volume.class, descriptor.getVolumeURI());   
                
                URI newVpoolURI = (URI)descriptor.getParameters().get(VolumeDescriptor.PARAM_VPOOL_CHANGE_VPOOL_ID);            
                URI oldVPoolURI = (URI)descriptor.getParameters().get(VolumeDescriptor.PARAM_VPOOL_OLD_VPOOL_ID); ;            
                            
                VirtualPool newVpool = _dbClient.queryObject(VirtualPool.class, newVpoolURI);
                VirtualPool oldVpool = _dbClient.queryObject(VirtualPool.class, oldVPoolURI);                           
                        
                // Phase 1 - Only support upgrade from RP+VPLEX to MetroPoint.
                // This includes:
                // Adding a secondary journal and possibly adding MP targets to an existing RP+VPLEX CG
                // as it is non-disruptive. Further CG Updates will be considered in the future.
                if (VirtualPool.vPoolSpecifiesRPVPlex(oldVpool)
                        && !VirtualPool.vPoolSpecifiesMetroPoint(oldVpool)
                        && VirtualPool.vPoolSpecifiesMetroPoint(newVpool)) {                    
                    upgradeRPVPlexToMetroPoint(sourceVolume, newVpool, oldVpool, rpSystem);
                }
                
                // Update the ProtectionSet with any newly added protection set objects
                // TODO support remove as well?
                ProtectionSet protectionSet = _dbClient.queryObject(ProtectionSet.class, sourceVolume.getProtectionSet());   
                updateProtectionSet(protectionSet, cgParams);
            }
            
            // Collect and update the protection system statistics to account for
            // the newly updated CG
            _log.info("Collecting RP statistics post CG update.");
            collectRPStatistics(rpSystem);            
            
            // Update the workflow state.
            _log.info("Update CG step completed");
            WorkflowStepCompleter.stepSucceded(token);                        
        } catch (Exception e) {
            _log.error("Failed updating cg: " + e.getStackTrace());
            doFailCgUpdateStep(volumeDescriptors, cgParams, rpSystemId, token, e);
            return false;
        }
        return true;
    }
    
    /**
     * Upgrades a RP+VPLEX CG to MetroPoint by adding a standby journal to the HA side.
     * 
     * Prerequiste: All RSets(volumes) in the CG must have had their HA sides already exported to RP in VPLEX.
     * 
     * @param sourceVolume A single source volume from the CG, we only need one.
     * @param rpSystem The rpSystem we're using
     */
    private void upgradeRPVPlexToMetroPoint(Volume sourceVolume, VirtualPool newVpool, VirtualPool oldVpool, ProtectionSystem rpSystem) {                
        // Grab the standby journal
        Volume standbyProdJournal = _dbClient.queryObject(Volume.class, sourceVolume.getSecondaryRpJournalVolume());                    
        _log.info(String.format("Upgrade RP+VPLEX CG to MetroPoint by adding new standby journal [%s] to the CG", standbyProdJournal.getLabel()));
                    
        // Add new standby jounrnal
        if (standbyProdJournal != null) {                                        
            RecoverPointClient rp = RPHelper.getRecoverPointClient(rpSystem);                      
            
            RecoverPointVolumeProtectionInfo protectionInfo = rp.getProtectionInfoForVolume(sourceVolume.getWWN());
            _log.info(String.format("RecoverPointVolumeProtectionInfo [%s] retrieved", protectionInfo.getRpProtectionName()));
                                  
            RPCopyRequestParams copyParams = new RPCopyRequestParams();
            copyParams.setCopyVolumeInfo(protectionInfo);         
            
            List<CreateVolumeParams> journaVols = new ArrayList<CreateVolumeParams>();
            CreateVolumeParams journalVolParams = new CreateVolumeParams();
            journalVolParams.setWwn(standbyProdJournal.getWWN());
            journalVolParams.setInternalSiteName(standbyProdJournal.getInternalSiteName());
            journaVols.add(journalVolParams);

            CreateCopyParams standbyProdCopyParams = new CreateCopyParams();
            standbyProdCopyParams.setName(standbyProdJournal.getRpCopyName());
            standbyProdCopyParams.setJournals(journaVols);
                     
            _log.info(String.format("Adding standby journal [%s] to teh RP CG...", standbyProdJournal.getLabel()));
            
            // TODO BH - Empty, not sure why we need this
            List<CreateRSetParams> rSets = new ArrayList<CreateRSetParams>();
            
            rp.addStandbyProductionCopy(standbyProdCopyParams, null, rSets, copyParams);
            _log.info("Standby journal added successfully.");
            
            // TODO Add new Targets if they exist ??
            
            // Next we need to update the vpool reference of any existing related volumes
            // that were referencing the old vpool.
            // We'll start by getting all source volums from the ViPR CG
            BlockConsistencyGroup viprCG = _dbClient.queryObject(BlockConsistencyGroup.class, sourceVolume.getConsistencyGroup());        
            List<Volume> allSourceVolumesInCG = BlockConsistencyGroupUtils.getActiveVplexVolumesInCG(viprCG, _dbClient, Volume.PersonalityTypes.SOURCE);
            
            for (Volume sourceVol : allSourceVolumesInCG) {
                // For each source volume, we'll get all the related volumes (Targets, Journals, Backing volumes for VPLEX...etc)
                Set<Volume> allRelatedVolumes = RPHelper.getAllRelatedVolumesForSource(sourceVol.getId(), _dbClient, true, true);
                // For each volume related to the source, check to see if it is referencing the old vpool.
                // If it is, update the reference and persist the change.
                for (Volume rpRelatedVol : allRelatedVolumes) {
                    if (rpRelatedVol.getVirtualPool().equals(oldVpool.getId())) {
                        rpRelatedVol.setVirtualPool(newVpool.getId());                                
                        _dbClient.persistObject(rpRelatedVol);
                        _log.info(String.format("Volume [%s] has had it's virtual pool updated to [%s].", rpRelatedVol.getLabel(), newVpool.getLabel()));
                    }
                }
            }
        }
    }
    
    /**
     * Workflow step method for creating a consistency group.
     *
     * @param rpSystem RP system
     * @param params parameters needed to create the CG
     * @param token the task
     * @return
     * @throws WorkflowException 
     */
    public boolean cgUpdateRollbackStep(URI rpSystemId, String token) throws WorkflowException {
        // nothing to do for now.
        WorkflowStepCompleter.stepSucceded(token);
        return true;
    }
    
    /**
     * process failure of creating a cg step.
     * 
     * @param volumeDescriptors volumes
     * @param cgParams cg parameters
     * @param protectionSetId protection set id
     * @param token task ID for audit
     * @param e exception
     * @throws InternalException
     */
    private void doFailCgUpdateStep(
            List<VolumeDescriptor> volumeDescriptors, CGRequestParams cgParams, URI protectionSetId,
            String token, Exception e) throws InternalException {
        // Record Audit operation. (vpool change only)
        if (VolumeDescriptor.getVirtualPoolChangeVolume(volumeDescriptors) != null) {
            AuditBlockUtil.auditBlock(_dbClient, OperationTypeEnum.CHANGE_VOLUME_VPOOL, true, AuditLogManager.AUDITOP_END, token);
        }
        stepFailed(token, e, "cgUpdateStep");
    }
}
