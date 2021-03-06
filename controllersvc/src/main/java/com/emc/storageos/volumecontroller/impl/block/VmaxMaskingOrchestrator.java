/**
* Copyright 2015 EMC Corporation
* All Rights Reserved
 */
/*
 * Copyright (c) 2013 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */

package com.emc.storageos.volumecontroller.impl.block;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.customconfigcontroller.CustomConfigConstants;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.Cluster;
import com.emc.storageos.db.client.model.ExportGroup;
import com.emc.storageos.db.client.model.ExportGroup.ExportGroupType;
import com.emc.storageos.db.client.model.ExportMask;
import com.emc.storageos.db.client.model.Initiator;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringMap;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.util.CommonTransformerFunctions;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.db.client.util.StringSetUtil;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.plugins.common.Constants;
import com.emc.storageos.svcs.errorhandling.model.ServiceError;
import com.emc.storageos.util.ExportUtils;
import com.emc.storageos.volumecontroller.BlockStorageDevice;
import com.emc.storageos.volumecontroller.impl.ControllerServiceImpl;
import com.emc.storageos.volumecontroller.impl.ControllerUtils;
import com.emc.storageos.volumecontroller.impl.HostIOLimitsParam;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.ExportOrchestrationTask;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.ExportRemoveVolumesOnAdoptedMaskCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.ExportTaskCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.VolumeUpdateCompleter;
import com.emc.storageos.volumecontroller.impl.smis.SmisUtils;
import com.emc.storageos.volumecontroller.impl.utils.ExportMaskUtils;
import com.emc.storageos.workflow.Workflow;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Collections2;
import com.google.common.collect.ListMultimap;

public class VmaxMaskingOrchestrator extends AbstractBasicMaskingOrchestrator {
    private static final Logger _log = LoggerFactory.getLogger(VmaxMaskingOrchestrator
            .class);

    private static final AtomicReference<BlockStorageDevice> VMAX_BLOCK_DEVICE = new
            AtomicReference<BlockStorageDevice>();
    public static final String VMAX_SMIS_DEVICE = "vmaxSmisDevice";
    public static final HashSet<String> INITIATOR_FIELDS = new HashSet<String>();

    static {
        INITIATOR_FIELDS.add("clustername");
        INITIATOR_FIELDS.add("hostname");
        INITIATOR_FIELDS.add("iniport");
    }

    public BlockStorageDevice getDevice() {
        BlockStorageDevice device = VMAX_BLOCK_DEVICE.get();
        synchronized (VMAX_BLOCK_DEVICE) {
            if (device == null) {
                device = (BlockStorageDevice)
                        ControllerServiceImpl.getBean(VMAX_SMIS_DEVICE);
                VMAX_BLOCK_DEVICE.compareAndSet(null, device);
            }
        }
        return device;
    }

    @Override
    public void exportGroupAddVolumes(URI storageURI, URI exportGroupURI,
                                      Map<URI, Integer> volumeMap, String token) throws Exception {
        ExportOrchestrationTask taskCompleter = null;
        try {
            BlockStorageDevice device = getDevice();
            taskCompleter = new ExportOrchestrationTask(exportGroupURI, token);
            StorageSystem storage = _dbClient.queryObject(StorageSystem.class, storageURI);
            ExportGroup exportGroup = _dbClient.queryObject(ExportGroup.class, exportGroupURI);
            logExportGroup(exportGroup, storageURI);
            // When adding volumes to an export group, this is the most common scenario.
            // The export group has a set of export masks already associated with it, and we
            // simply need to determine which of those masks require the volumes and add them.
            // Exceptions to this are documented in the logic.
            if (exportGroup.getExportMasks() != null) {
                // Set up workflow steps.
                Workflow workflow = _workflowService.getNewWorkflow(
                        MaskingWorkflowEntryPoints.getInstance(), "exportGroupAddVolumes", true,
                        token);
                
                Collection<URI> initiatorIds = Collections2.transform(StringSetUtil.get(exportGroup.getInitiators()),
                        CommonTransformerFunctions.FCTN_STRING_TO_URI);
                
                if (!determineExportGroupCreateSteps(workflow, null, device, storage, exportGroup, 
                		new ArrayList<URI>(initiatorIds), volumeMap, true, token)) {
                	throw DeviceControllerException.exceptions.exportGroupCreateFailed(new Exception("Export Group Add Volume Failed"));
                }
                
                String successMessage = String.format(
                		"Successfully added volumes to export on StorageArray %s",
                		storage.getLabel());
                workflow.executePlan(taskCompleter, successMessage);
            } else {
            	if (exportGroup.hasInitiators()) {
            		_log.info("There are no masks for this export. Need to create anew.");
            		List<URI> initiatorURIs = new ArrayList<URI>();
            		for (String initiatorURIStr : exportGroup.getInitiators()) {
            			initiatorURIs.add(URI.create(initiatorURIStr));
            		}
            		// Invoke the export group create operation,
            		// which should in turn create a workflow operations to
            		// create the export for the newly added volume(s).
            		exportGroupCreate(storageURI, exportGroupURI, initiatorURIs, volumeMap, token);
            	} else {
            		_log.warn("There are no initiators for export group: " + exportGroup.getLabel());                    
                    // Additional logic to ensure the task is closed out in the case where no workflow was really generated.
                	taskCompleter.ready(_dbClient);
                	_log.info("No volumes pushed to array because either they already exist " +
                			"or there were no initiators added to the export yet.");
            	}
            }
        } catch (Exception ex) {
        	_log.error("ExportGroup Orchestration failed.", ex);
        	if (taskCompleter != null) {
        		ServiceError serviceError = DeviceControllerException.errors.jobFailedMsg(ex.getMessage(), ex);
        		taskCompleter.error(_dbClient, serviceError);
        	}
        }
    }

    @Override
    public void exportGroupAddInitiators(URI storageURI, URI exportGroupURI,
                                         List<URI> initiatorURIs, String token) throws Exception {
        BlockStorageDevice device = getDevice();
        String previousStep = null;
        ExportOrchestrationTask taskCompleter = new ExportOrchestrationTask(exportGroupURI, token);
        StorageSystem storage = _dbClient.queryObject(StorageSystem.class, storageURI);
        ExportGroup exportGroup = _dbClient.queryObject(ExportGroup.class, exportGroupURI);
        logExportGroup(exportGroup, storageURI);
        // Set up workflow steps.
        Workflow workflow = _workflowService.getNewWorkflow(
                MaskingWorkflowEntryPoints.getInstance(), "exportGroupAddInitiators", true, token);
        Map<URI, List<URI>> zoneMasksToInitiatorsURIs = new HashMap<URI, List<URI>>();
        Map<URI, Map<URI, Integer>> zoneNewMasksToVolumeMap = new HashMap<URI, Map<URI, Integer>>();
        Map<URI, ExportMask> refreshedMasks = new HashMap<URI, ExportMask>();

        Map<String, URI> portNameToInitiatorURI = new HashMap<String, URI>();
        List<String> portNames = new ArrayList<String>();
        // Populate data structures to track initiators
        processInitiators(exportGroup, initiatorURIs, portNames, portNameToInitiatorURI);

        // Populate a map of volumes on the storage device
        List<BlockObject> blockObjects = new ArrayList<BlockObject>();
        Map<URI, Integer> volumeMap = new HashMap<URI, Integer>();
        if (exportGroup != null && exportGroup.getVolumes() != null) {
            for (Map.Entry<String, String> entry : exportGroup.getVolumes().entrySet()) {
                URI boURI = URI.create(entry.getKey());
                Integer hlu = Integer.valueOf(entry.getValue());
                BlockObject bo = BlockObject.fetch(_dbClient, boURI);
                if (bo.getStorageController().equals(storageURI)) {
                    volumeMap.put(boURI, hlu);
                    blockObjects.add(bo);
                }
            }
        }

        // Determine initial mapping of compute resources to list of initiators
        Map<String, List<URI>> resourceToInitiators = mapInitiatorsToComputeResource(exportGroup, initiatorURIs);

        boolean anyOperationsToDo = false;
        Set<URI> partialMasks = new HashSet<>();
        Map<String, Set<URI>> initiatorToExportMaskPlacementMap =
                determineInitiatorToExportMaskPlacements(exportGroup, storageURI,
                        resourceToInitiators, device.findExportMasks(storage, portNames, false), portNameToInitiatorURI, partialMasks);

        if (!initiatorToExportMaskPlacementMap.isEmpty()) {
            Map<URI, ExportMaskPolicy> policyCache = new HashMap<>();
            // The logic contained here is trying to place the initiators that were passed down in the
            // request. If we are in this path where the initiatorToExportMaskPlacementMap is not empty, then there
            // are several cases why we got here:
            //
            // 1). An ExportMask has been found that is associated with the ExportGroup and it
            //     is supposed to be the container for the compute resources that we are attempting
            //     to add initiators for.
            // 2). An ExportMask has been found that is on the array. It may not be associated with the
            //     ExportGroup, but it is supposed to be the container for the compute resources that
            //     we are attempting to add initiators for.
            // 3). An ExportMask has been found that is on the array. It may not be associated with the
            //     ExportGroup, but it has the initiators that we are trying to add
            // 4). One of the above possibilities + an initiator that cannot be placed. The use-case here
            //     would someone adds a new initiator for an existing host and a new host to a cluster export.
            List<URI> initiatorsToPlace = new ArrayList<URI>();
            initiatorsToPlace.addAll(initiatorURIs);

            // This loop will determine a list of volumes to update per export mask
            Map<URI, Map<URI, Integer>> existingMasksToUpdateWithNewVolumes = new HashMap<URI, Map<URI, Integer>>();
            Map<URI, Set<Initiator>> existingMasksToUpdateWithNewInitiators = new HashMap<URI, Set<Initiator>>();
            for (Map.Entry<String, Set<URI>> entry : initiatorToExportMaskPlacementMap.entrySet()) {
                URI initiatorURI = portNameToInitiatorURI.get(entry.getKey());
                if (initiatorURI == null || exportGroup == null) {
                    // This initiator does not exist or it is not one of the initiators passed to the function
                    continue;
                }
                Initiator initiator = _dbClient.queryObject(Initiator.class, initiatorURI);
                // Get a list of the ExportMasks that were matched to the initiator
                List<URI> exportMaskURIs = new ArrayList<URI>();
                exportMaskURIs.addAll(entry.getValue());
                List<ExportMask> masks = _dbClient.queryObject(ExportMask.class, exportMaskURIs);
                _log.info(String.format("Trying to place initiator %s", entry.getKey()));
                for (ExportMask mask : masks) {
                    // Check for NO_VIPR.  If found, avoid this mask.
                    if (mask.getMaskName() != null && mask.getMaskName().toUpperCase().contains(ExportUtils.NO_VIPR)) {
                        _log.info(String.format("ExportMask %s disqualified because the name contains %s (in upper or lower case) to exclude it", 
                                mask.getMaskName(), ExportUtils.NO_VIPR));
                        continue;
                    }

                    _log.info(String.format("Trying to place initiator %s in mask %s", entry.getKey(), mask.getMaskName()));
                    if (mask.getInactive() && !mask.getStorageDevice().equals(storageURI)) {
                        continue;
                    }
                    // This refresh call should be revisited, it should have been made in 
                    // determineInitiatorToExportMaskPlacements or findExportMasks
                    if (!refreshedMasks.containsKey(mask.getId())) {
                        mask = device.refreshExportMask(storage, mask);
                        refreshedMasks.put(mask.getId(), mask);
                    }
                    ExportMaskPolicy policy = getExportMaskPolicy(policyCache, device, storage, mask);
                    // Check if the mask that as was found/selected for the initiator already
                    // has the initiator in it. The only time that this would be untrue is
                    // if we are attempting to add new hosts to a cluster export. In this case,
                    // the determineInitiatorToExportMaskPlacements() would have found the ExportMask for
                    // the cluster to place the initiators, but it would not have them added
                    // yet. The below logic will add the volumes necessary.
                    if (mask.hasInitiator(initiatorURI.toString())) {
                        _log.info(String.format("mask %s has initiator %s", mask.getMaskName(),
                                initiator.getInitiatorPort()));
                        // Loop through all the block objects that have been exported
                        // to the storage system and place only those that are not
                        // already in the masks to the placement list
                        for (BlockObject blockObject : blockObjects) {
                        	// Determine if the block object belongs in this mask or not, given the mask, mask policy,
                        	// blockObject properties, and so on.
                        	if (!mask.hasExistingVolume(blockObject.getWWN()) && !mask.hasVolume(blockObject.getId())) {
                        		String volumePolicyName = ControllerUtils.getAutoTieringPolicyName(blockObject.getId(), _dbClient);
                        		if (((volumePolicyName == null || volumePolicyName.equalsIgnoreCase(Constants.NONE.toString())) && (policy.tierPolicies == null || policy.tierPolicies.size() == 0)) ||
                        				((volumePolicyName != null && policy.tierPolicies != null && policy.tierPolicies.size() == 1 && policy.tierPolicies.contains(volumePolicyName)))) {
                        			_log.info(String.format("mask doesn't have volume %s yet, need to add it", blockObject.getId()));
                        			Map<URI, Integer> newVolumesMap = existingMasksToUpdateWithNewVolumes
                        					.get(mask.getId());
                        			if (newVolumesMap == null) {
                        				newVolumesMap = new HashMap<URI, Integer>();
                        				existingMasksToUpdateWithNewVolumes.put(mask.getId(),
                        						newVolumesMap);
                        			}
                        			newVolumesMap.put(blockObject.getId(),
                        					volumeMap.get(blockObject.getId()));
                        		}
                        	} else {
                    			_log.info(String.format("not adding volume %s to mask ", blockObject.getId(), mask.getMaskName()));
                        	}
                        }
                        // The initiator has been placed - it is in an already existing export
                        // for which case, we may just have to add volumes to it
                        initiatorsToPlace.remove(initiatorURI);
                    } else {
                    	Set<URI> existingInitiatorIds = ExportMaskUtils.getAllInitiatorsForExportMask(_dbClient, mask);
                    	if (existingInitiatorIds.isEmpty()) {
                			_log.info(String.format("not adding initiator to %s mask %s because there are no initiators associated with this mask",
                					initiatorURI, mask.getMaskName()));
                    	}

                    	// This mask does not contain the initiator, but it may not belong to the same compute resource.
                    	for (URI existingInitiatorId : existingInitiatorIds) {
                    		Initiator existingInitiator = _dbClient.queryObject(Initiator.class, existingInitiatorId);
                            if (existingInitiator == null) {
                                _log.warn(String.format("Initiator %s was found to be associated with ExportMask %s, but no longer exists in the DB",
                                        existingInitiatorId, mask.getId()));
                                continue;
                            }
                    		if ((existingInitiator.getHost() != null && existingInitiator.getHost().equals(initiator.getHost())) ||
                    			(existingInitiator.getClusterName() != null && existingInitiator.getClusterName().equals(initiator.getClusterName()))) {
                    			
                    			// We don't want to add this initiator to the mask in the condition where:
                    			// 1. The export group type is cluster, and
                    			// 2. The export mask is a "partial" mask, meaning it contains a single host, and
                    			// 3. The host of this initiator is not the host associated with the mask.
                    			// Place the initiator in this ExportMask.
                    			if (exportGroup.forCluster() && !policy.isCascadedIG() && 
                    				((existingInitiator.getHost() == null || !existingInitiator.getHost().equals(initiator.getHost())))) {
                        			_log.info(String.format("not adding initiator to %s mask %s because it is likely part of another mask in the cluster", 
                        					initiatorURI, mask.getMaskName()));
                        			continue;
                    			}
                    			
                    			Set<Initiator> existingMaskInitiators = existingMasksToUpdateWithNewInitiators.get(mask.getId());
                    			if (existingMaskInitiators == null) {
                    				existingMaskInitiators = new HashSet<Initiator>();
                    				existingMasksToUpdateWithNewInitiators.put(mask.getId(), existingMaskInitiators);
                    			}
                    			_log.info(String.format("adding initiator to %s mask %s because it was found to be in the same compute resource", 
                    					initiatorURI, mask.getMaskName()));
                    			existingMaskInitiators.add(initiator);
                    			// The initiator has been placed - it is not in the export, we will have to
                    			// add it to the mask
                    			initiatorsToPlace.remove(initiatorURI);
                    		} else {
                    			_log.info(String.format("not adding initiator to %s mask %s because it doesn't belong to the same compute resource", 
                    					initiatorURI, mask.getMaskName()));
                    		}
                    	}
                    } 

                    // Update the list of volumes and initiators for the mask
                    Map<URI, Integer> volumeMapForExistingMask = existingMasksToUpdateWithNewVolumes
                            .get(mask.getId());
                    if (volumeMapForExistingMask != null && !volumeMapForExistingMask.isEmpty()) {
                        mask.addVolumes(volumeMapForExistingMask);
                    }

                    Set<Initiator> initiatorSetForExistingMask = existingMasksToUpdateWithNewInitiators
                            .get(mask.getId());
                    if (initiatorSetForExistingMask != null && initiatorSetForExistingMask.isEmpty()) {
                        mask.addInitiators(initiatorSetForExistingMask);
                    }

                    updateZoningMap(exportGroup, mask, false);

                    _dbClient.updateAndReindexObject(mask);
                    exportGroup.addExportMask(mask.getId());
                    _dbClient.updateAndReindexObject(exportGroup);
                }
            }

            // The initiatorsToPlace was used in the foreach initiator loop to see
            // which initiators already exist in a mask. If it is non-empty,
            // then it means there are initiators that are new,
            // so let's add them to the main tracker
            if (!initiatorsToPlace.isEmpty()) {
                Map<String, List<URI>> computeResourceToInitiators =
                        mapInitiatorsToComputeResource(exportGroup, initiatorsToPlace);
                for (Map.Entry<String, List<URI>> resourceEntry : computeResourceToInitiators.entrySet()) {
                    String computeKey = resourceEntry.getKey();
                    List<URI> computeInitiatorURIs = resourceEntry.getValue();
                    _log.info(String.format("New export masks for %s", computeKey));
                    GenExportMaskCreateWorkflowResult result = generateExportMaskCreateWorkflow(workflow, previousStep, storage,
                            exportGroup, computeInitiatorURIs, volumeMap, token);
                    previousStep = result.getStepId();
                    zoneNewMasksToVolumeMap.put(result.getMaskURI(), volumeMap);
                    anyOperationsToDo = true;
                }
            }

            _log.info(String.format("existingMasksToUpdateWithNewVolumes.size = %d",
                    existingMasksToUpdateWithNewVolumes.size()));

            // At this point we have a mapping of all the masks that we need to update with new volumes
            for (Map.Entry<URI, Map<URI, Integer>> entry : existingMasksToUpdateWithNewVolumes
                    .entrySet()) {
                ExportMask mask = _dbClient.queryObject(ExportMask.class, entry.getKey());
                Map<URI, Integer> volumesToAdd = entry.getValue();
                _log.info(String.format("adding these volumes %s to mask %s",
                        Joiner.on(",").join(volumesToAdd.keySet()), mask.getMaskName()));
                List<URI> volumeURIs = new ArrayList<URI>();
                volumeURIs.addAll(volumesToAdd.keySet());
                List<ExportMask>masks = new ArrayList<ExportMask>();
                masks.add(mask);
                previousStep = generateZoningAddVolumesWorkflow(workflow, previousStep,
                        exportGroup, masks, volumeURIs);
                previousStep = generateExportMaskAddVolumesWorkflow(workflow, previousStep, storage, exportGroup,
                        mask, volumesToAdd);
                anyOperationsToDo = true;
            }

            // At this point we have a mapping of all the masks that we need to update with new initiators
            for (Map.Entry<URI, Set<Initiator>> entry : existingMasksToUpdateWithNewInitiators.entrySet()) {
                ExportMask mask = _dbClient.queryObject(ExportMask.class, entry.getKey());
                Set<Initiator> initiatorsToAdd = entry.getValue();
                List<URI> initiatorsURIs = new ArrayList<URI>();
                for (Initiator initiator : initiatorsToAdd) {
                    initiatorsURIs.add(initiator.getId());
                }
                _log.info(String.format("adding these initiators %s to mask %s",
                        Joiner.on(",").join(initiatorsURIs), mask.getMaskName()));
                Map<URI, List<URI>> maskToInitiatorsMap = new HashMap<URI, List<URI>>();
                maskToInitiatorsMap.put(mask.getId(), initiatorsURIs);
                previousStep = generateZoningAddInitiatorsWorkflow(workflow, previousStep,
                        exportGroup, maskToInitiatorsMap);
                previousStep = generateExportMaskAddInitiatorsWorkflow(workflow, previousStep, storage, exportGroup, mask,
                        initiatorsURIs, null, token);
                anyOperationsToDo = true;
            }

        } else {
            _log.info("There are no masks for this export. Need to create anew.");
            // Create two steps, one for Zoning, one for the ExportGroup actions.
            // This step is for zoning. It is not specific to a single NetworkSystem,
            // as it will look at all the initiators and targets and compute the
            // zones required (which might be on multiple NetworkSystems.)
            for (Map.Entry<String, List<URI>> resourceEntry : resourceToInitiators.entrySet()) {
                String computeKey = resourceEntry.getKey();
                List<URI> computeInitiatorURIs = resourceEntry.getValue();
                _log.info(String.format("New export masks for %s", computeKey));
                GenExportMaskCreateWorkflowResult result = generateExportMaskCreateWorkflow(workflow, previousStep, storage,
                        exportGroup, computeInitiatorURIs, volumeMap, token);
                zoneNewMasksToVolumeMap.put(result.getMaskURI(), volumeMap);
                previousStep = result.getStepId();
                anyOperationsToDo = true;
            }
        }
        
        if (anyOperationsToDo) {
            if (!zoneNewMasksToVolumeMap.isEmpty()) {
                List<URI> exportMaskList = new ArrayList<URI>();
                exportMaskList.addAll(zoneNewMasksToVolumeMap.keySet());
                Map<URI, Integer> overallVolumeMap = new HashMap<URI, Integer>();
                for (Map<URI, Integer> oneVolumeMap : zoneNewMasksToVolumeMap.values()) {
                    overallVolumeMap.putAll(oneVolumeMap);
                }
                previousStep = generateZoningCreateWorkflow(workflow, previousStep, exportGroup, exportMaskList, overallVolumeMap);
            }
            if (!zoneMasksToInitiatorsURIs.isEmpty()) {
                previousStep = generateZoningAddInitiatorsWorkflow(workflow, previousStep, exportGroup, zoneMasksToInitiatorsURIs);
            }
            String successMessage = String.format(
                    "Successfully exported to initiators on StorageArray %s", storage.getLabel());
            workflow.executePlan(taskCompleter, successMessage);
        } else {
            taskCompleter.ready(_dbClient);
        }
    }

    @Override
    public void exportGroupRemoveInitiators(URI storageURI, URI exportGroupURI,
                                            List<URI> initiatorURIs, String token) throws Exception {
        BlockStorageDevice device = getDevice();
        ExportOrchestrationTask taskCompleter = new ExportOrchestrationTask(exportGroupURI, token);
        StorageSystem storage = _dbClient.queryObject(StorageSystem.class, storageURI);
        ExportGroup exportGroup = _dbClient.queryObject(ExportGroup.class, exportGroupURI);
        logExportGroup(exportGroup, storageURI);
        // Set up workflow steps.
        Workflow workflow = _workflowService.getNewWorkflow(
                MaskingWorkflowEntryPoints.getInstance(), "exportGroupRemoveInitiators", true,
                token);

        Map<String, URI> portNameToInitiatorURI = new HashMap<String, URI>();
        List<String> portNames = new ArrayList<String>();
        // Populate data structures to track initiators
        processInitiators(exportGroup, initiatorURIs, portNames, portNameToInitiatorURI);

        // Populate a map of volumes on the storage device associated with this ExportGroup
        List<BlockObject> blockObjects = new ArrayList<BlockObject>();
        if ( exportGroup != null) {
            for (Map.Entry<String, String> entry : exportGroup.getVolumes().entrySet()) {
            	URI boURI = URI.create(entry.getKey());
            	BlockObject bo = BlockObject.fetch(_dbClient, boURI);
            	if (bo.getStorageController().equals(storageURI)) {
            		blockObjects.add(bo);
            	}
            }
        }

        Map<URI, Boolean> initiatorIsPartOfFullListFlags =
        		flagInitiatorsThatArePartOfAFullList(exportGroup, initiatorURIs);

        boolean anyOperationsToDo = false;
        Map<URI, ExportMask> refreshedMasks = new HashMap<URI, ExportMask>();
        if (exportGroup != null && exportGroup.getExportMasks() != null) {
            // There were some exports out there that already have some or all of the
            // initiators that we are attempting to remove. We need to only
            // remove the volumes that the user added to these masks
            Map<String, Set<URI>> matchingExportMaskURIs = getInitiatorToExportMaskMap(exportGroup);

            // This loop will determine a list of volumes to update per export mask
            Map<URI, List<URI>> existingMasksToRemoveInitiator = new HashMap<URI, List<URI>>();
            Map<URI, List<URI>> existingMasksToRemoveVolumes = new HashMap<URI, List<URI>>();
            Map<URI, List<URI>> existingMasksToCoexistInitiators = new HashMap<URI, List<URI>>();
            for (Map.Entry<String, Set<URI>> entry : matchingExportMaskURIs.entrySet()) {
                URI initiatorURI = portNameToInitiatorURI.get(entry.getKey());
                if (initiatorURI == null) {
                    // Entry key points to an initiator that was not passed in the remove request
                    continue;
                }
                Initiator initiator = _dbClient.queryObject(Initiator.class, initiatorURI);

                // Get a list of the ExportMasks that were matched to the initiator
                // go through the initiators and figure out the proper intiator and volume ramifications
                // to the existing masks.
                List<URI> exportMaskURIs = new ArrayList<URI>();
                exportMaskURIs.addAll(entry.getValue());
                List<ExportMask> masks = _dbClient.queryObject(ExportMask.class, exportMaskURIs);
                _log.info(String.format("initiator %s masks {%s}", initiator.getInitiatorPort(),
                        Joiner.on(',').join(exportMaskURIs)));
                for (ExportMask mask : masks) {
                    if (mask == null || mask.getInactive() || !mask.getStorageDevice().equals(storageURI)) {
                        continue;
                    }
                    
                    if (!refreshedMasks.containsKey(mask.getId())) {
                        //refresh the export mask always
                        mask = device.refreshExportMask(storage, mask);
                        refreshedMasks.put(mask.getId(), mask);
                    }

        			_log.info(String.format("mask %s has initiator %s", mask.getMaskName(),
        					initiator.getInitiatorPort()));
        			if (!mask.hasUserInitiator(initiator.getId())) {
        				_log.info(String.format("Initiator %s was not added by ViPR, so ViPR cannot remove it.  No action will be taken for this initiator", initiator.getId()));
                        List<URI> initiators = existingMasksToCoexistInitiators.get(mask.getId());
                        if (initiators == null) {
                            initiators = new ArrayList<URI>();
                            existingMasksToCoexistInitiators.put(mask.getId(), initiators);
                        }
                        if (!initiators.contains(initiator.getId())) {
                            _log.info("Adding co-exist initiator {} in mask {} to existingMasksToCoexistInitiators", 
                                    initiator.getInitiatorPort(), mask.getMaskName());
                            initiators.add(initiator.getId());
                        }
        				continue;
        			}

        			// If there's more than one export group, that means there's my export group plus another one.
        			// Best to just leave that initiator alone.
        			Set<URI> exportGroupURIs = new HashSet<URI>();
        			if (ExportUtils.isExportMaskShared(_dbClient, mask.getId(), exportGroupURIs)) {
                        // Need to do another check against the initiator. If the initiator is not in any of
                        // the other ExportGroups, then we can remove it.
                        exportGroupURIs.remove(exportGroupURI);
                        if (ExportUtils.checkIfAnyExportGroupsContainInitiator(_dbClient, exportGroupURIs, initiator)) {
                            _log.info(String.format("Initiator %s is in an ExportMask that is shared by ExportGroups %s, so we will not remove it",
                                    initiator.getInitiatorPort(), Joiner.on(',').join(exportGroupURIs)));
                        } else {
                            _log.info(String.format("Initiator %s is in an ExportMask that is shared by ExportGroups %s, " +
                                            "but the initiator is not in any of them. Will remove it from the ExportMask.",
                                    initiator.getInitiatorPort(), Joiner.on(',').join(exportGroupURIs)));
                            List<URI> initiators = existingMasksToRemoveInitiator.get(mask.getId());
                            if (initiators == null) {
                                initiators = new ArrayList<URI>();
                                existingMasksToRemoveInitiator.put(mask.getId(), initiators);
                            }
                            if (!initiators.contains(initiator.getId())) {
                                initiators.add(initiator.getId());
                            }
                        }
        			} else {
        				_log.info(String.format("We can remove initiator %s from mask %s", initiator.getInitiatorPort(), mask.getMaskName()));
        				List<URI> initiators = existingMasksToRemoveInitiator.get(mask.getId());
        				if (initiators == null) {
        					initiators = new ArrayList<URI>();
        					existingMasksToRemoveInitiator.put(mask.getId(), initiators);
        				}
        				if (!initiators.contains(initiator.getId())) {
        					initiators.add(initiator.getId());
        				}
        			}

        			if (mask.getCreatedBySystem()) {

        				// Remove volumes from masks that aren't in our export group if our initiator was involved.
        				// Also check to see if that volume is already in another export group with that initiator.
        				List<URI> volumesToRemove = new ArrayList<URI>();
        				for (String volumeIdStr : exportGroup.getVolumes().keySet()) {
        					URI egVolumeID = URI.create(volumeIdStr);
        					if (mask.getUserAddedVolumes().containsValue(volumeIdStr)) {
        						int exportGroupsWithVolume = ExportUtils.getNumberOfExportGroupsWithVolume(initiator, egVolumeID, _dbClient);
        						if (exportGroupsWithVolume > 1) {
        							_log.info(String.format("Found that my volume %s is in another export group with this initiator %s, so we shouldn't remove it from the mask",
        									volumeIdStr, initiator.getInitiatorPort()));
        						} else {
        							// If this initiator is part of the full list of initiators for
        							// compute resource, then it implies, that we will be removing
        							// it from the export. In such case, we would need to remove the
        							// related volumes from the export.
        							// If the initiator is part of partial list of initiators for
        							// a compute resource, then we should only bother to remove the
        							// initiator and not touch the volumes
        							if (initiatorIsPartOfFullListFlags.get(initiatorURI)) {
        								_log.info(String.format("We can potentially remove volume %s from mask %s", volumeIdStr, mask.getMaskName()));
        								if (!volumesToRemove.contains(egVolumeID)) {
        									volumesToRemove.add(egVolumeID);
        								}
        							}
        						}
        					}
        				}

        				// Place the volumes to remove into the map corresponding to the map we're currently processing.
        				if (!volumesToRemove.isEmpty()) {
        					// Only remove volumes from masks as a side-effect of initiator removal for non-initiator export group types.
        					// Otherwise this logic may remove volumes from masks that have references to other initiators to the same host.
        					if (!exportGroup.forInitiator()) {
        						List<URI> removeVolumesList = existingMasksToRemoveVolumes.get(mask.getId());
        						if (removeVolumesList == null) {
        							removeVolumesList = new ArrayList<URI>();
        							existingMasksToRemoveVolumes.put(mask.getId(),
        									removeVolumesList);
        						}
        						removeVolumesList.addAll(volumesToRemove);
        					} else {
        						// Just a reminder to the world in the case where Initiator is used in this odd situation.
        						_log.info("Removing volumes from an Initiator type export group as part of an initiator removal is not supported.");
        					}
        				}
        			} else {
        				// Loop through all the block objects that have been
        				// exported to the storage system and place only those that
        				// are not already in the masks to the remove list
        				for (BlockObject blockObject : blockObjects) {
        					if (mask.hasUserCreatedVolume(blockObject.getWWN())) {
        						// If any system-created initiator in the mask is not in our list to remove, then we shouldn't remove
        						// the block object because another initiator in a ViPR export group is depending on that object being there.
        						//
        						// Once all user-added initiators are slated for removal, the block volume can be removed
        						// as well.
        						boolean okToRemove = true;
        						if (mask.getUserAddedInitiators() != null) {
        							for (URI maskInitiatorId : URIUtil.toURIList(mask.getUserAddedInitiators().values())) {
        								if (!initiatorURIs.contains(maskInitiatorId)) {
        									okToRemove = false;
        									_log.info("Will not remove block object {} because there are initiators " +
        											"remaining in the export mask that were created by the system",
        											String.valueOf(blockObject.getId()));
        									break;
        								}
        							}
        						}

        						// We can only remove the volume if all the initiators associated with the export group
        						// and mask are getting removed.  Otherwise we are still referencing an initiator (or more)
        						// in the export group that is in this mask, and we should not remove the volume(s).
        						if (okToRemove && !initiatorIsPartOfFullListFlags.get(initiatorURI)) {
        							okToRemove = false;
        							_log.info("Will not remove block object {} because there are initaitors " +
        									"remaining in the masking view that were created by the sytem",
        									String.valueOf(blockObject.getId()));
        						}

        						if (okToRemove) {
        							List<URI> removeVolumesList = existingMasksToRemoveVolumes
        									.get(mask.getId());
        							if (removeVolumesList == null) {
        								removeVolumesList = new ArrayList<URI>();
        								existingMasksToRemoveVolumes.put(mask.getId(),
        										removeVolumesList);
        							}
        							removeVolumesList.add(blockObject.getId());
        						}
        					}
        				}
        				// At this point we have a mapping of masks to objects that we want to remove
        			}
        		}
        	}
            Set<URI> masksGettingRemoved = new HashSet<URI>();

            // In this loop we are trying to remove those initiators that exist
            // on a mask that ViPR created.
            String previousStep = null;
            for (Map.Entry<URI, List<URI>> entry : existingMasksToRemoveInitiator.entrySet()) {
                ExportMask mask = _dbClient.queryObject(ExportMask.class, entry.getKey());
                List<URI> initiatorsToRemove = entry.getValue();
                //CTRL-8846 fix : Compare against all the initiators
                if (initiatorsToRemove.size() >= ExportUtils.getExportMaskAllInitiators(mask, _dbClient).size()) {
                    masksGettingRemoved.add(mask.getId());
                    // For this case, we are attempting to remove all the
                    // initiators in the mask. This means that we will have to delete the
                    // exportGroup
                    _log.info(String.format("mask %s has removed all "
                            + "initiators, we are going to delete the mask from the " + "array",
                            mask.getMaskName()));
                    List<ExportMask> exportMasks = new ArrayList<ExportMask>();
                    exportMasks.add(mask);
                    previousStep = generateZoningDeleteWorkflow(workflow, previousStep, exportGroup,
                          exportMasks);
                    previousStep = generateExportMaskDeleteWorkflow(workflow, previousStep, storage, exportGroup,
                            mask, null);
                    exportGroup.removeExportMask(mask.getId());
                    _dbClient.updateAndReindexObject(exportGroup);
                    anyOperationsToDo = true;
                } else {
                    _log.info(String.format("mask %s - going to remove the "
                            + "following initiators %s", mask.getMaskName(),
                            Joiner.on(',').join(initiatorsToRemove)));
                    Map<URI, List<URI>> maskToInitiatorsMap = new HashMap<URI, List<URI>>();
                    maskToInitiatorsMap.put(mask.getId(), initiatorsToRemove);
                    previousStep = generateZoningRemoveInitiatorsWorkflow(workflow, previousStep, exportGroup,
                            maskToInitiatorsMap);

                    previousStep = generateExportMaskRemoveInitiatorsWorkflow(workflow, previousStep, storage,
                            exportGroup, mask, initiatorsToRemove, true);
                    anyOperationsToDo = true;
                }

                // Determine if there are any more initiators from our export group in this mask.  If not, remove the
                // reference to the mask from the export group.
                boolean removeMaskReference = true;
                if (exportGroup.hasInitiators() && mask.getInitiators() != null) {
                	for (URI maskInitiatorId : ExportUtils.getExportMaskAllInitiators(mask, _dbClient)) {
                		if ((exportGroup.getInitiators().contains(maskInitiatorId.toString())) &&
                				(!initiatorURIs.contains(maskInitiatorId))) {
                			removeMaskReference = false;
                		}
                	}
                }
                if (removeMaskReference) {
                    _log.info(String.format("removing reference to mask %s from export group %s", mask.getMaskName(), exportGroup.getLabel()));
                    exportGroup.removeExportMask(mask.getId());
                    _dbClient.updateAndReindexObject(exportGroup);
                } else {
                    for (URI initiatorToRemove : initiatorsToRemove) {
                    	exportGroup.removeInitiator(initiatorToRemove);
                    }
                }

            }

            // In this loop we are trying to remove volumes from masks that
            // ViPR did not create. We have no control over the initiators defined in
            // these masks. We will be removing only those volumes that are applicable
            // for the storage array and ExportGroup.
            for (Map.Entry<URI, List<URI>> entry : existingMasksToRemoveVolumes.entrySet()) {
                if (masksGettingRemoved.contains(entry.getKey())) {
                    _log.info("Mask {} is getting removed, no need to remove volumes from it",
                            entry.getKey().toString());
                    continue;
                }

                ExportMask mask = _dbClient.queryObject(ExportMask.class, entry.getKey());
                List<URI> volumesToRemove = entry.getValue();
                List<URI> initiatorsToRemove = existingMasksToRemoveInitiator.get(mask.getId());
                if (initiatorsToRemove != null) {
                    List<URI> initiatorsInExportMask = StringSetUtil.stringSetToUriList(mask.getInitiators());
                    initiatorsInExportMask.removeAll(initiatorsToRemove);
                    if (!initiatorsInExportMask.isEmpty()) {
                        // There are still some initiators in this ExportMask
                        _log.info(String.
                                format("ExportMask %s would have remaining initiators {%s} that require access to {%s}. " +
                                                "Not going to remove any of the volumes",
                                        mask.getMaskName(), Joiner.on(',').join(initiatorsInExportMask),
                                        Joiner.on(',').join(volumesToRemove)));
                        continue;
                    }
                }

                Collection<String> volumesToRemoveURIStrings =
                        Collections2.transform(volumesToRemove,
                                CommonTransformerFunctions.FCTN_URI_TO_STRING);
                List<String> exportMaskVolumeURIStrings =
                        new ArrayList<String>(mask.getVolumes().keySet());
                exportMaskVolumeURIStrings.removeAll(volumesToRemoveURIStrings);

                if (exportMaskVolumeURIStrings.isEmpty()) {
                    _log.info(String.format("All the volumes (%s) from mask %s will be removed, so will have to remove the whole mask",
                            Joiner.on(",").join(volumesToRemove), mask.getMaskName()));
                    // Order matters!  Above this would be any remove initiators that would impact other masking views.
                    // Be sure to always remove anything inside the mask before removing the mask itself.
                    previousStep = generateZoningDeleteWorkflow(workflow, previousStep, exportGroup, Arrays.asList(mask));
                    previousStep = generateExportMaskDeleteWorkflow(workflow, previousStep, storage, exportGroup, mask, null);
                    anyOperationsToDo = true;
                } else {
                    ExportTaskCompleter completer = new ExportRemoveVolumesOnAdoptedMaskCompleter(
                            exportGroupURI, mask.getId(), volumesToRemove, token);
                    _log.info(String.format("A subset of volumes will be removed from mask %s: %s",
                            mask.getMaskName(), Joiner.on(",").join(volumesToRemove)));
                    List<ExportMask> masks = new ArrayList<ExportMask>();
                    masks.add(mask);
                    previousStep = generateZoningRemoveVolumesWorkflow(workflow, previousStep,
                            exportGroup, masks, volumesToRemove);

                    previousStep = generateExportMaskRemoveVolumesWorkflow(workflow, previousStep, storage, exportGroup,
                            mask, volumesToRemove, completer);
                    anyOperationsToDo = true;

                    // Determine if there are any more initiators from our export group in this mask.  If not, remove the
                    // reference to the mask from the export group.
                    boolean removeMaskReference = true;
                    if (exportGroup.hasInitiators() && mask.getUserAddedInitiators() != null) {
                        for (Map.Entry<String, String> maskInitiatorId : mask
                                .getUserAddedInitiators().entrySet()) {
                            if ((exportGroup.getInitiators().contains(maskInitiatorId.getValue())) &&
                                    (!initiatorURIs.contains(URI.create(maskInitiatorId.getValue())))) {
                                removeMaskReference = false;
                            }
                        }
                    }
                    if (removeMaskReference) {
                        _log.info(String.format("removing reference to mask %s from export group %s", mask.getMaskName(), exportGroup.getLabel()));
                        exportGroup.removeExportMask(mask.getId());
                        _dbClient.updateAndReindexObject(exportGroup);
                    } else {
                        exportGroup.removeVolumes(volumesToRemove);
                        _dbClient.updateAndReindexObject(exportGroup);
                    }
                }
            }
            for (Map.Entry<URI, List<URI>> entry : existingMasksToCoexistInitiators.entrySet()) {
                // this was a co-exist initiator - We need to remove any zone references and
                // we assume the initiator was removed from the DB and clean it
                ExportMaskUtils.removeMaskCoexistInitiators(dbModelClient, entry.getKey(), entry.getValue());
            }
        }

        if (anyOperationsToDo) {
            String successMessage = String.format(
                    "Successfully removed exports for initiators on StorageArray %s",
                    storage.getLabel());
            workflow.executePlan(taskCompleter, successMessage);
        } else {
            taskCompleter.ready(_dbClient);
        }
    }
    
    @Override
    protected boolean useComputedMaskName() {
    	return true;
    }
    
    @Override
    protected String getMaskingCustomConfigTypeName(String exportType) {
    	if(ExportGroupType.Cluster.name().equals(exportType)){
    		return CustomConfigConstants.VMAX_CLUSTER_MASKING_VIEW_MASK_NAME;
    	} else {
    		return CustomConfigConstants.VMAX_HOST_MASKING_VIEW_MASK_NAME;
    	}
    }    

    /**
     * Routine contains logic to create an export mask on the array
     *
     * @param workflow - Workflow object to create steps against
     * @param previousStep - [optional] Identifier of workflow step to wait for
     * @param device - BlockStorageDevice implementation
     * @param storage - StorageSystem object representing the underlying array
     * @param exportGroup - ExportGroup object representing Bourne-level masking
     * @param initiatorURIs - List of Initiator URIs
     * @param volumeMap - Map of Volume URIs to requested Integer HLUs
     * @param zoningStepNeeded - Determines whether zone step is needed
     * @param token - Identifier for the operation
     * @throws Exception
     */
    public boolean determineExportGroupCreateSteps(Workflow workflow, String previousStep,
                                                 BlockStorageDevice device, StorageSystem storage, ExportGroup exportGroup,
                                                 List<URI> initiatorURIs, Map<URI, Integer> volumeMap, boolean zoningStepNeeded, String token) throws Exception {
        // If we didn't create any workflows by the end of this method, we can return an appropriate exception (instead of the Task just hanging)
    	boolean flowCreated = false;

    	// Pre-load the initiators that are expected as part of this creation request.
        List<Initiator> initiators = _dbClient.queryObject(Initiator.class, initiatorURIs);

        // A map of compute resources to their ports.  ListMultimap is used for shorthand of a single 
        // compute resource to a collection of ports.
        ListMultimap<String, String> computeResourceToPortNames = ArrayListMultimap.create();

        // A mapping of port name (11:22:33:44:55:66:77:88) to initiator URI
    	Map<String, URI> portNameToInitiatorURI = new HashMap<String, URI>();

    	// The port names that are part of this request.
    	List<String> portNames = new ArrayList<String>();
        
        // Populate data structures to track initiators
        processInitiators(exportGroup, initiators, portNames, portNameToInitiatorURI,
                computeResourceToPortNames);

        // A map of compute resources to initiators
        Map<String, List<URI>> resourceToInitiators = mapInitiatorsToComputeResource(exportGroup, initiatorURIs);

        // Find the qualifying export masks that are associated with any or all the ports in
        // portNames. We will have to do processing differently based on whether
        // or there is an existing ExportMasks.
        // 
        // In the case of clusters, we try to find the export mask that contains a subset of initiators
        // of the cluster, so we can build onto it.
        Set<URI> partialMasks = new HashSet<>();
        Map<String, Set<URI>> initiatorToExportMaskPlacementMap =
                determineInitiatorToExportMaskPlacements(exportGroup, storage.getId(),
                        resourceToInitiators, device.findExportMasks(storage, portNames, false), portNameToInitiatorURI, partialMasks); 
        
        // If we didn't find any export masks for any compute resources, then it's a total loss, and we need to 
        // create new masks for each compute resource.
        //
        // TODO: I'm guessing this logic isn't necessary and the "else" statement below will take care of this situation
        // as well.  Certainly not as clearly as this will, but regardless.
        if (initiatorToExportMaskPlacementMap.isEmpty()) {
            _log.info(String.format("No existing mask found w/ initiators { %s }", Joiner.on(",")
                    .join(portNames)));
            if (!initiatorURIs.isEmpty()) {
                Map<String, List<URI>> computeResourceToInitiators =
                        mapInitiatorsToComputeResource(exportGroup, initiatorURIs);
                for (Map.Entry<String, List<URI>> resourceEntry :
                        computeResourceToInitiators.entrySet()) {
                    String computeKey = resourceEntry.getKey();
                    List<URI> computeInitiatorURIs = resourceEntry.getValue();
                    _log.info(String.format("New export masks for %s", computeKey));
                    GenExportMaskCreateWorkflowResult result = generateExportMaskCreateWorkflow(workflow, previousStep, storage,
                            exportGroup, computeInitiatorURIs, volumeMap, token);
                    previousStep = result.getStepId();
                    flowCreated = true;
                }
            }
        } else {
            Map<URI, ExportMaskPolicy> policyCache = new HashMap<>();
            _log.info(String.format("Mask(s) found w/ initiators {%s}. "
                    + "MatchingExportMaskURIs {%s}, portNameToInitiators {%s}", Joiner.on(",")
                    .join(portNames), Joiner.on(",").join(initiatorToExportMaskPlacementMap.keySet()), Joiner
                    .on(",").join(portNameToInitiatorURI.entrySet())));
            // There are some initiators that already exist. We need to create a
            // workflow that create new masking containers or updates masking
            // containers as necessary.

            // These data structures will be used to track new initiators - ones
            // that don't already exist on the array
            List<URI> initiatorURIsCopy = new ArrayList<URI>();
            initiatorURIsCopy.addAll(initiatorURIs);

            // This loop will determine a list of volumes to update per export mask
            Map<URI, Map<URI, Integer>> existingMasksToUpdateWithNewVolumes = new HashMap<URI, Map<URI, Integer>>();
            Map<URI, Set<Initiator>> existingMasksToUpdateWithNewInitiators = new HashMap<URI, Set<Initiator>>();
            Set<URI> initiatorsForNewExport = new HashSet<>();

            // Volumes with no mask:  A map of initiators to volumes that need to be on those ports.
            Map<URI, Map<URI, Integer>> volumesWithNoMask = new HashMap<URI, Map<URI, Integer>>();

            // Map of export masks found along with their mask policies
            Map<ExportMask, ExportMaskPolicy> masterMaskMap = new HashMap<ExportMask, ExportMaskPolicy>();
            
            // Special case for VMAX with a cluster compute resource:
            //
            // When multiple export masks combine to make a cluster mask, we wish to leverage them.
            //
            // At this point, we have already gotten our qualified masks for the compute resources, however
            // the "default" flow will not consider cases where multiple export masks, when combined, make up
            // a cluster resource.  We need to add to the placement Map that the cluster's qualifying masks
            // are those per-node masks.  Logic in the orchestrator will ensure these multi-masks will be 
            // treated as a single mask, where volumes will be added to both.
            //
            // Logic is as follows: Attempt to discover which ports have not been placed in the map yet (specific to VMAX),
            // and add those ports to the map in the circumstance where we are doing cluster and the
            // existing mask is already handling multiple hosts.
            //
            // In the case of brownfield cluster, some of these port to ExportMask may be missing because the array doesn't
            // have them yet.  Find this condition and add the additional ports to the map.
            if (exportGroup.forCluster() || exportGroup.forHost()) {
            	updatePlacementMapForCluster(exportGroup, resourceToInitiators,	initiatorToExportMaskPlacementMap);
            }
            
            // This loop processes all initiators that were found in the existing export masks.
            // It doesn't necessary mean that all initiators requested are in an export mask.
            // In the case where initiators are missing and not represented by other masks, we need
            // to mark that these initiators need to be added to the existing masks.
            for (Map.Entry<String, Set<URI>> entry : initiatorToExportMaskPlacementMap.entrySet()) {
            	URI initiatorURI = portNameToInitiatorURI.get(entry.getKey());
            	Initiator initiator = _dbClient.queryObject(Initiator.class, initiatorURI);
            	// Keep track of those initiators that have been found to exist already
            	// in some export mask on the array
            	initiatorURIsCopy.remove(initiatorURI);

            	List<URI> exportMaskURIs = new ArrayList<URI>();
            	exportMaskURIs.addAll(entry.getValue());
            	List<ExportMask> masks = _dbClient.queryObject(ExportMask.class, exportMaskURIs);
            	_log.info(String.format("initiator %s masks {%s}", initiator.getInitiatorPort(),
            			Joiner.on(',').join(exportMaskURIs)));

            	// This section will look through greenfield or brownfield scenarios and will discover if the initiator
            	// is not yet added to the mask. Note the masks were all refreshed by #device.findExportMasks() above
            	for (ExportMask mask : masks) {
            		_log.info(String.format("processing mask %s and initiator %s", mask.getMaskName(),
            				initiator.getInitiatorPort()));
            		
                    // Check for NO_VIPR.  If found, avoid this mask.
                    if (mask.getMaskName() != null && mask.getMaskName().toUpperCase().contains(ExportUtils.NO_VIPR)) {
                        _log.info(String.format("ExportMask %s disqualified because the name contains %s (in upper or lower case) to exclude it", 
                                mask.getMaskName(), ExportUtils.NO_VIPR));
                        continue;
                    }
                    //TODO Find ways to avoid getting this export detais info twice.
                    ExportMaskPolicy exportMaskDetails = getExportMaskPolicy(policyCache, device, storage, mask);
                    
                    
                    // Check if the ExportMask applies to more than one host. Since
            		// ViPR will be creating ExportMask per compute resource
            		// (single host or cluster), the only way that an existing mask
            		// applies to multiple hosts is when it was for a cluster
            		// export. If we find that to be the case,
            		// we should be able to create ExportMasks for it.
            		boolean hasMultipleHosts = maskAppliesToMultipleHosts(mask);
            		boolean createHostExportWhenClusterExportExists =
            				(hasMultipleHosts && exportGroup.forHost());
            		//One node cluster Case - Always create a new MV if existing mask doesn't contain Cascaded IG.
            		boolean createClusterExportWhenHostExportExists =
            				(exportGroup.forCluster() && !exportMaskDetails.isCascadedIG());
            		if (createClusterExportWhenHostExportExists ||
            			createHostExportWhenClusterExportExists) {
            			// It may turn out that we find these initiators already covered by a collection of 
            			// masks for cluster purposes.  If that's the case, we figure that out below and these
            			// "new" exports will never see the light of day.
            			initiatorsForNewExport.add(initiatorURI);
            			continue;
            		}

            		// We're still OK if the mask contains ONLY initiators that can be found
            		// in our export group, because we would simply add to them.
            		if (mask.getInitiators() != null) {
            			for (String existingMaskInitiatorStr : mask.getInitiators()) {
            				Initiator existingMaskInitiator = _dbClient.queryObject(Initiator.class, URI.create(existingMaskInitiatorStr));
            				// Now look at it from a different angle.  Which one of our export group initiators
            				// are NOT in the current mask?  And if so, if it belongs to the same host as an existing one,
            				// we should add it to this mask.
            				if ((initiator != null && initiator.getId() != null) &&
            						// and we don't have an entry already to add this initiator to the mask
            						(!existingMasksToUpdateWithNewInitiators.containsKey(mask.getId()) || !existingMasksToUpdateWithNewInitiators.get(mask.getId()).contains(initiator)) &&
            						// and the initiator exists in the first place
            						(existingMaskInitiator != null && 
            						// and this is a host export for this host, or...
            						(exportGroup.forHost() && initiator.getHost() != null && initiator.getHost().equals(existingMaskInitiator.getHost()) ||
            						// this is a cluster export for this cluster
            						(exportGroup.forCluster() && initiator.getClusterName() != null && initiator.getClusterName().equals(existingMaskInitiator.getClusterName()))))) {
            					// Add to the list of initiators we need to add to this mask
            					Set<Initiator> existingMaskInitiators = existingMasksToUpdateWithNewInitiators.get(mask.getId());

            					if (existingMaskInitiators == null) {
            						existingMaskInitiators = new HashSet<Initiator>();
            						existingMasksToUpdateWithNewInitiators.put(mask.getId(), existingMaskInitiators);
            					}

            					// If this initiator is already in the mask, add a key to mark that we need to add the export mask reference
            					// to the export group later.
            					if (!mask.hasInitiator(initiator.getId().toString())) {
                					existingMaskInitiators.add(initiator);
                					_log.info(String.format("initiator %s needs to be added to mask %s", initiator.getInitiatorPort(), mask.getMaskName()));
            					} 
            				}
            			}
            		}
            	}
            }
            
            // For adding volumes to existing masks, we want to do it by compute resource.
            // This loop will normalize the port, mask map to a compute resource -> list of masks map.
            Map<String, Set<URI>> resourceMaskMap = createResourceMaskMap(portNameToInitiatorURI, resourceToInitiators,
					initiatorToExportMaskPlacementMap);            
            
            // Each volume needs to be seen by all initiators in the compute resource.
            // This loop will find a mask (or create one) for each compute resource for the volumes desired.
            for (Entry<String, Set<URI>> resourceMaskEntry : resourceMaskMap.entrySet()) {
                // Get a list of the ExportMasks that were matched to the initiator
                // This list will be full of volumes, but as we find existing masks for volumes, we remove from this list.
            	// Whatever isn't removed at the end needs to become a new mask.
                volumesWithNoMask.clear();
                List<URI> initiatorsForResource = resourceToInitiators.get(resourceMaskEntry.getKey());
                for (URI initiatorId : initiatorsForResource) {
                	if (volumesWithNoMask.get(initiatorId) == null) {
                		volumesWithNoMask.put(initiatorId, new HashMap<URI, Integer>());
                	}
                	volumesWithNoMask.get(initiatorId).putAll(volumeMap);
                }

                // Gather policies of the export masks, if they're not in the map yet.
                // Store them in a map so we don't have to keep asking for them.
                Map<ExportMask, ExportMaskPolicy> masksMap = new HashMap<ExportMask, ExportMaskPolicy>();
                List<ExportMask> exportMasks = _dbClient.queryObject(ExportMask.class, resourceMaskEntry.getValue());
                for (ExportMask mask : exportMasks) {
                    // Check for NO_VIPR.  If found, avoid this mask.
                    if (mask.getMaskName() != null && mask.getMaskName().toUpperCase().contains(ExportUtils.NO_VIPR)) {
                        _log.info(String.format("ExportMask %s disqualified because the name contains %s (in upper or lower case) to exclude it", 
                                mask.getMaskName(), ExportUtils.NO_VIPR));
                        continue;
                    }

                    // We keep a master mask map to reduce churn on the array/provider
                	if (masterMaskMap.get(mask) == null) {
                        ExportMaskPolicy policy = getExportMaskPolicy(policyCache, device, storage, mask);
                		masterMaskMap.put(mask, policy);
                		_log.info("Export mask policy: " + policy);
                	}
                	// This map is all we need for the upcoming commands.
                	masksMap.put(mask, masterMaskMap.get(mask));
                }
                
            	// Apply rules engine to the list of export masks.
                // This method will analyze the masks for each resource and will determine the best mask or masks
                // for the volumes that still need masks for this resource and will update the maps accordingly.
            	if (!applyVolumesToMasksUsingRules(storage, exportGroup,
						existingMasksToUpdateWithNewVolumes,
						volumesWithNoMask, masksMap, existingMasksToUpdateWithNewInitiators, partialMasks, token)) {
            		// Fatal error occurred.  The caller already set the task object and service code,
            		// so we just need to return that we failed.
            		return false;
            	}

                // This is the case where we couldn't find a mask that was appropriate to add the volumes,
                // even though several masks matched the export mask criteria at first.
                if (volumesWithNoMask.size() > 0) {
                	List<URI> leftoverInitiatorsForNewExport = new ArrayList<URI>();
                	// Figure out the initiators we "missed" for the volumes in this loop
                	for (URI initiatorId : volumesWithNoMask.keySet()) {
                		Initiator initiator = _dbClient.queryObject(Initiator.class, initiatorId);
                		if (initiator != null) {
                			leftoverInitiatorsForNewExport.add(initiator.getId());
                			initiatorsForNewExport.remove(initiator.getId());
                		}
                	}
                    Map<String, List<URI>> computeResourceToInitiators =
                            mapInitiatorsToComputeResource(exportGroup,
                                    leftoverInitiatorsForNewExport);
                    for (Map.Entry<String, List<URI>> resourceEntry :
                            computeResourceToInitiators.entrySet()) {
                        String computeKey = resourceEntry.getKey();
                        List<URI> computeInitiatorURIs = resourceEntry.getValue();
                        Initiator initiator = _dbClient.queryObject(Initiator.class, computeInitiatorURIs.get(0));
                        _log.info(String.format("Residual mask needed: New export masks for %s", computeKey));
                        GenExportMaskCreateWorkflowResult result = generateExportMaskCreateWorkflow(workflow, previousStep, storage,
                                exportGroup, computeInitiatorURIs, 
                                volumesWithNoMask.get(initiator.getId()),
                                token);
                        flowCreated = true;
                        previousStep = result.getStepId();
                        //Add zoning
						if (zoningStepNeeded) {
							String zoningStep = workflow.createStepId();
							List<URI> masks = new ArrayList<URI>();
							masks.add(result.getMaskURI());
							previousStep = generateZoningCreateWorkflow(workflow, previousStep, exportGroup, masks,
									volumeMap, zoningStep);
						}
                        
                    }
                }
                
                // Now if our efforts to find homes for the volumes in existing masking views yielded success,
                // this is the time to trim down the list of initiators that will be used to create NEW masking
                // views.  Go through the initiators for the resource we originally used to populate the "volumesWithNoMask"
                // map and see if any volumes still exist.  If not, remove those "new" initiators.
                for (URI initiatorId : initiatorsForResource) {
                	if (volumesWithNoMask.get(initiatorId) == null) {
                		if (initiatorURIsCopy.remove(initiatorId)) {
                			_log.info("Determined that we do not need to create a new mask for initiator [1]: " + initiatorId);
                		}
                		if (initiatorsForNewExport.remove(initiatorId)) {
                			_log.info("Determined that we do not need to create a new mask for initiator [2]: " + initiatorId);
                		}
                	}
                }
            }

            _log.info(String.format("existingMasksToUpdateWithNewVolumes.size = %d", existingMasksToUpdateWithNewVolumes.size()));

            // This is the case where we have an existing export for a cluster and we
            // want to create another export against one of the hosts in the cluster,
            // or vice-versa.
            if (!initiatorsForNewExport.isEmpty()) {
                if (exportGroup.forCluster() && !initiatorURIsCopy.isEmpty()) {
                    // Clustered export group create request and there are essentially
                    // new and existing initiators. We'll take what's not already
                    // exported to and add it to the list of initiators to export
                    initiatorsForNewExport.addAll(initiatorURIsCopy);
                    // Clear the copy list because we're going to be creating exports
                    // for these. (There's code below that uses initiatorURIsCopy to
                    // determine what exports to update)
                    initiatorURIsCopy.clear();
                }
                Map<String, List<URI>> computeResourceToInitiators =
                        mapInitiatorsToComputeResource(exportGroup,
                                initiatorsForNewExport);
                for (Map.Entry<String, List<URI>> resourceEntry :
                        computeResourceToInitiators.entrySet()) {
                    String computeKey = resourceEntry.getKey();
                    List<URI> computeInitiatorURIs = resourceEntry.getValue();
                    _log.info(String.format("New export masks for %s", computeKey));
                    GenExportMaskCreateWorkflowResult result = generateExportMaskCreateWorkflow(workflow, previousStep, storage,
                            exportGroup, computeInitiatorURIs, volumeMap, token);
                    flowCreated = true;
                    previousStep = result.getStepId();
					if (zoningStepNeeded) {
						String zoningStep = workflow.createStepId();
						List<URI> masks = new ArrayList<URI>();
						masks.add(result.getMaskURI());
						previousStep = generateZoningCreateWorkflow(workflow,previousStep, exportGroup, masks, volumeMap,
								zoningStep);
					}
                }
            }

            // The initiatorURIsCopy was used in the foreach initiator loop to see
            // which initiators already exist in a mask. If it is non-empty,
            // then it means there are initiators that are new,
            // so let's add them to the main tracker
            Map<String, List<URI>> newComputeResources =
                    mapInitiatorsToComputeResource(exportGroup, initiatorURIsCopy);

            // At this point we have the necessary data structures populated to
            // determine the workflow steps. We are going to create new masks
            // and/or add volumes to existing masks.
            if (newComputeResources != null && !newComputeResources.isEmpty()) {
                for (Map.Entry<String, List<URI>> entry :
                        newComputeResources.entrySet()) {
                    // We have some brand new initiators, let's add them to new masks
                    _log.info(String.format("New mask needed for compute resource %s",
                            entry.getKey()));
                    GenExportMaskCreateWorkflowResult result = generateExportMaskCreateWorkflow(workflow, previousStep, storage,
                            exportGroup, entry.getValue(), volumeMap, token);
                    flowCreated = true;
                    previousStep = result.getStepId();
                    //Add zoning step
					if (zoningStepNeeded) {
						String zoningStep = workflow.createStepId();
						List<URI> masks = new ArrayList<URI>();
						masks.add(result.getMaskURI());
						previousStep = generateZoningCreateWorkflow(workflow,previousStep, exportGroup, masks, volumeMap,
								zoningStep);
					}
                }
            }

            // Put volumes in the existing masks that need them.
            for (Map.Entry<URI, Map<URI, Integer>> entry : existingMasksToUpdateWithNewVolumes
                    .entrySet()) {
                ExportMask mask = _dbClient.queryObject(ExportMask.class, entry.getKey());
                updateZoningMap(exportGroup, mask, true);
                Map<URI, Integer> volumesToAdd = entry.getValue();
                _log.info(String.format("adding these volumes %s to mask %s",
                        Joiner.on(",").join(volumesToAdd.keySet()), mask.getMaskName()));
                previousStep = generateZoningAddVolumesWorkflow(workflow, previousStep,
                        exportGroup, Arrays.asList(mask), new ArrayList<URI>(volumesToAdd.keySet()));
                previousStep = generateExportMaskAddVolumesWorkflow
                        (workflow, previousStep, storage, exportGroup, mask, volumesToAdd);
                flowCreated = true;
                exportGroup.addExportMask(mask.getId());
                _dbClient.updateAndReindexObject(exportGroup);                
            }

            // Put new initiators in existing masks that are missing them.
            for (Map.Entry<URI, Set<Initiator>> entry : existingMasksToUpdateWithNewInitiators.entrySet()) {
            	ExportMask mask = _dbClient.queryObject(ExportMask.class, entry.getKey());
            	// Make sure the mask is one that we are going to add volumes to.  Otherwise, don't bother
            	// modifying it or making it part of our export group.
            	if (!existingMasksToUpdateWithNewVolumes.containsKey(mask.getId())) {
            		_log.info(String.format("Not adding initiators to mask: %s because we found we don't need to change the mask", mask.getMaskName()));
            		continue;
            	}
                updateZoningMap(exportGroup, mask, true);

            	exportGroup.addExportMask(mask.getId());
            	_dbClient.updateAndReindexObject(exportGroup);
            	Set<Initiator> initiatorsToAdd = entry.getValue();
            	if (!initiatorsToAdd.isEmpty()) {
            		List<URI> initiatorsURIs = new ArrayList<URI>();
            		for (Initiator initiator : initiatorsToAdd) {
            			initiatorsURIs.add(initiator.getId());
            		}
            		_log.info(String.format("adding these initiators %s to mask %s",
            				Joiner.on(",").join(initiatorsURIs), mask.getMaskName()));
                Map<URI, List<URI>> maskToInitiatorsMap = new HashMap<URI, List<URI>>();
                maskToInitiatorsMap.put(mask.getId(), initiatorsURIs);
                    previousStep = generateZoningAddInitiatorsWorkflow(workflow, previousStep, exportGroup,
                            maskToInitiatorsMap);
                    previousStep = generateExportMaskAddInitiatorsWorkflow(workflow, previousStep, storage,
                            exportGroup, mask, initiatorsURIs, volumeMap.keySet(), token);

                    flowCreated = true;
            	}
            }
        }

        /*
        // DO NOT CHECK IN UNCOMMENTED (this is convenient for debugging)
        if (flowCreated) {
            ExportOrchestrationTask completer = new ExportOrchestrationTask(
                    exportGroup.getId(), token);
            completer.ready(_dbClient);
        	return false;
        }
        */
        
        // Catch if no flows were created; close off the task
        if (!flowCreated) {
            ExportOrchestrationTask completer = new ExportOrchestrationTask(
                    exportGroup.getId(), token);
            completer.ready(_dbClient);
            return true;
        }
        
        return true;
    }

	/**
	 * Creates a map of compute resource to export masks associated with that resource.
	 * 
	 * @param portNameToInitiatorURI port name -> initiator URI simple map
	 * @param resourceToInitiators compute resource -> initiator ports
	 * @param initiatorToExportMaskPlacementMap initiator port -> masks
	 * @return map of compute resource -> export masks that qualify for analysis
	 */
	private Map<String, Set<URI>> createResourceMaskMap(Map<String, URI> portNameToInitiatorURI,
			Map<String, List<URI>> resourceToInitiators,
			Map<String, Set<URI>> initiatorToExportMaskPlacementMap) {
		Map<String, Set<URI>> resourceMaskMap = new HashMap<String, Set<URI>>();
		for (Entry<String, List<URI>> resourceToInitiatorEntry : resourceToInitiators.entrySet()) {
			// For each resource, we have a list of Initiator URIs
			for (Map.Entry<String, Set<URI>> entry : initiatorToExportMaskPlacementMap.entrySet()) {
				// The initiator to exportmask map only has ports; find that port and its corresponding initiator URI
				URI portNameURI = portNameToInitiatorURI.get(entry.getKey());
				if (portNameURI != null) {
					if (resourceToInitiatorEntry.getValue().contains(portNameURI)) {
						if (resourceMaskMap.get(resourceToInitiatorEntry.getKey()) == null) {
							resourceMaskMap.put(resourceToInitiatorEntry.getKey(), new HashSet<URI>());
						}
						resourceMaskMap.get(resourceToInitiatorEntry.getKey()).addAll(entry.getValue());
					}
				}
			}
		}
		return resourceMaskMap;
	}

    /**
     * Special case for VMAX with a cluster compute resource:
     * 
     *  In the case where a mask may contain a subset of nodes of a cluster, we wish to leverage it.
     *  
     *  Logic is as follows: Attempt to discover which ports have not been placed in the map yet (specific to VMAX),
     *  and add those ports to the map in the circumstance where we are doing cluster and the
     *  existing mask is already handling multiple hosts.
     * 
     *  In the case of brownfield cluster, some of these port to ExportMask may be missing because the array doesn't
     *  have them yet.  Find this condition and add the additional ports to the map.
     *  
     * @param exportGroup export group 
     * @param resourceToInitiators resource -> initiator list
     * @param initiatorToExportMaskPlacementMap placement mask map from the default orchestrator
	 */
    private void updatePlacementMapForCluster(ExportGroup exportGroup,
    		Map<String, List<URI>> resourceToInitiators,
    		Map<String, Set<URI>> initiatorToExportMaskPlacementMap) {
    	// double check we're dealing with cluster
    	if (exportGroup.forCluster() || exportGroup.forHost()) {
    		// Safety, ensure the map has been created.
			if (initiatorToExportMaskPlacementMap == null) {
				initiatorToExportMaskPlacementMap = new HashMap<String, Set<URI>>();
			}

    		// Check each compute resource's initiator list
    		for (Map.Entry<String, List<URI>> entry : resourceToInitiators.entrySet()) {
    			List<URI> initiatorSet = entry.getValue();
    			for (URI initiatorURI : initiatorSet) {
    				Initiator initiator = _dbClient.queryObject(Initiator.class, initiatorURI);

    				// Is this initiator covered in the map yet?
    				Set<URI> exportMasksToAdd = new HashSet<URI>();
    				if (!initiatorToExportMaskPlacementMap.keySet().contains(Initiator.normalizePort(initiator.getInitiatorPort()))) {
    					// Can we find an existing intiatorToExportMaskURIMap entry that contains the same compute resource?
    					for (String port : initiatorToExportMaskPlacementMap.keySet()) {
    						// Verify it's the same compute resource
    						Initiator existingInitiator = ExportUtils.getInitiator(Initiator.toPortNetworkId(port), _dbClient);
    						if (existingInitiator != null && 
    							((exportGroup.forCluster() && existingInitiator.getClusterName().equals(initiator.getClusterName())) ||
    							 (exportGroup.forHost() && existingInitiator.getHostName().equals(initiator.getHostName())))) {
    							// Go through the masks, verify they are all multi-host already
    							for (URI maskId : initiatorToExportMaskPlacementMap.get(port)) {
    								ExportMask mask = _dbClient.queryObject(ExportMask.class, maskId);
    								if (exportGroup.forHost() || maskAppliesToMultipleHosts(mask)) {
    									// Create a new map entry for this initiator.
    									exportMasksToAdd.add(mask.getId());
    								}
    							}
    						}            							
    					}
    				}

    				if (!exportMasksToAdd.isEmpty()) {
    					initiatorToExportMaskPlacementMap.put(Initiator.normalizePort(initiator.getInitiatorPort()), exportMasksToAdd);
    				}
    			}
    		}
    	}
    }

	/**
	 * This method will call the method to apply a set of business rules to the volumes
	 * required to be added to masking views.  See the "applyVolumesToMasksUsingRule" method
	 * documentation for exact rule logic.
	 * 
	 * @param exportGroup export group
	 * @param existingMasksToUpdateWithNewVolumes masks to update with new volumes if criteria is met
	 * @param volumesWithNoMask a list that empties as we find homes for volumes
	 * @param maskToInitiatorsMap map of export masks to the initiators they need to cover
	 * @param partialMasks TODO
	 * @param token task id
	 * @param masks masks associated with the initiator
	 * @param rule rule number from above
	 * @return true if the task succeeded to search for homes for all the volumes.  false if a fatal error occurred.
	 */
	private boolean applyVolumesToMasksUsingRules(StorageSystem storage, ExportGroup exportGroup,
			Map<URI, Map<URI, Integer>> existingMasksToUpdateWithNewVolumes,
			Map<URI, Map<URI, Integer>> volumesWithNoMask,
			Map<ExportMask, ExportMaskPolicy> masksMap, 
			Map<URI, Set<Initiator>> maskToInitiatorsMap,
			Set<URI> partialMasks, String token) {
	    boolean isVMAX3 = storage.checkIfVmax3();
		// Rule 1: See if there is a mask that matches our policy and only our policy
		if (!applyVolumesToMasksUsingRule(exportGroup, token,
				existingMasksToUpdateWithNewVolumes,
				volumesWithNoMask, masksMap, maskToInitiatorsMap, partialMasks, 1, isVMAX3)) {
			return false; // inner method took care of the servicecode/completer
		}

		// Rule 2: See if there is any mask that contains more than 1 policy at all, which would
		//         indicate a cascaded storage group, which we can use, OR a non-fast non-cascaded storage group
		//         that has a volume in it that is associated with another policy, which is also usable by us.
		if (!applyVolumesToMasksUsingRule(exportGroup, token,
				existingMasksToUpdateWithNewVolumes,
				volumesWithNoMask, masksMap, maskToInitiatorsMap, partialMasks, 2, isVMAX3)) {
			return false;
		}

		// Rule 3: See if there is a non-fast masking view that is pointing to a non-cascading SG.
		//         We can create a "phantom" storage group and add this volume to the storage group
		//         that's already associated with this mask. This doesn't apply to VMAX3
		if (!applyVolumesToMasksUsingRule(exportGroup, token,
				existingMasksToUpdateWithNewVolumes,
				volumesWithNoMask, masksMap, maskToInitiatorsMap, partialMasks, 3, isVMAX3)) {
			return false;
		}
		
		return true;
	}

	/**
	 * Apply business rules to "add" volumes to specific export masks.
	 * 
	 * Currently implemented rules:
	 * Rule 1. If you find an exact match of your volume with a mask's policy, use it.
	 * Rule 2. If you find a mask with multiple policies using cascaded storage groups, use it.
	 * Rule 3. If you find a mask with non-cascading storage group and a non-fast SG, use it.
	 *         (phantom will be searched/created in this case)
	 * 
	 * @param exportGroup export group
	 * @param token task id
	 * @param existingMasksToUpdateWithNewVolumes masks to update with new volumes if criteria is met
	 * @param volumesWithNoMask a list that empties as we find homes for volumes
	 * @param masks masks associated with the initiator
	 * @param maskToInitiatorsMap map of export masks to the initiators they need to cover
	 * @param partialMasks list of masks that contain a subset of initiators for the compute resource requested
	 * @param rule rule number from above
	 * @return true if the task succeeded to search for homes for all the volumes.  false if a fatal error occurred.
	 */
	private boolean applyVolumesToMasksUsingRule(ExportGroup exportGroup,
			String token, 
			Map<URI, Map<URI, Integer>> existingMasksToUpdateWithNewVolumes,
			Map<URI, Map<URI, Integer>> volumesWithNoMask, 
			Map<ExportMask,ExportMaskPolicy> masks, 
			Map<URI, Set<Initiator>> maskToInitiatorsMap,
			Set<URI> partialMasks, int rule, boolean isVMAX3) {
		
		// populate a map of mask to initiator ID for the analysis loop.
		Map<URI, Set<URI>> maskToInitiatorsToAddMap = new HashMap<URI, Set<URI>>();
		if (maskToInitiatorsMap != null) {
			for (Entry<URI, Set<Initiator>> entry : maskToInitiatorsMap.entrySet()) {
				for (Initiator initiator : entry.getValue()) {
					if (!maskToInitiatorsToAddMap.containsKey(entry.getKey())) {
						maskToInitiatorsToAddMap.put(entry.getKey(), new HashSet<URI>());
					}
					maskToInitiatorsToAddMap.get(entry.getKey()).add(initiator.getId());
				}
			}
		}
		
		ListMultimap<URI, URI> volumesWithMask = ArrayListMultimap.create();
		for (ExportMask mask : ExportMaskUtils.sortMasksByEligibility(masks, exportGroup)) {
			// We need to see if the volume also exists the mask,
			// if it doesn't then we'll add it to the list of volumes to add.
			ExportMaskPolicy policy = masks.get(mask);
			for (URI initiatorId : volumesWithNoMask.keySet()) {

				// Check to ensure the initiator is in this mask or in the list of initiators we intend to add to this mask.
				if ((mask.getInitiators() == null || !mask.getInitiators().contains(initiatorId.toString())) &&
					(!maskToInitiatorsToAddMap.containsKey(mask.getId()) || !maskToInitiatorsToAddMap.get(mask.getId()).contains(initiatorId))) {
					continue;
				}
				
		        Map<URI, VirtualPool> uriVirtualPoolMap = new HashMap<URI, VirtualPool>();
				for (URI boURI : volumesWithNoMask.get(initiatorId).keySet()) {
					BlockObject bo = Volume.fetchExportMaskBlockObject(_dbClient, boURI);
					if (bo != null && !mask.hasExistingVolume(bo)) {
							
						// Make sure the volume hasn't already been added to the user add volume list.
						if (mask.hasUserCreatedVolume(bo.getId())) {
							// Remove this combo from the 
						}
						
						// Make sure the volume hasn't already been found for this initiator and BO combination.
						// If that's the case, we can simply move onto the next volume because the volumesWithMask
						// object is already reflected that the volume is covered.
						if (volumesWithMask.containsKey(initiatorId) && volumesWithMask.get(initiatorId).contains(boURI)) {
							continue;
						}

						// Make sure the mask matches the fast policy of the volume
						boolean match = false;

						// Make sure this volume hasn't already been placed in this masking view.
						// If it is, we still need to mark that the volume has found a home for this specific initiator, so 
						// set the match flag to true so the volumesWithMask will get marked properly.
						if (existingMasksToUpdateWithNewVolumes.containsKey(mask.getId()) && existingMasksToUpdateWithNewVolumes.get(mask.getId()).containsKey(boURI)) {
							match = true;
						} else {
							List<Initiator> initiators = _dbClient.queryObjectField(Initiator.class, "iniport", Arrays.asList(initiatorId));
							Initiator initiator = initiators.get(0);
							_log.info(String.format("Pre-existing Mask Rule %d: volume %s is not exposed to initiator %s in mask %s.  Checking rule.", rule, bo.getLabel(), 
									initiator.getInitiatorPort(), mask.getMaskName()));
							// Check if the requested HLU for the volume is
							// already taken by a pre-existing volume.
							Integer requestedHLU = volumesWithNoMask.get(initiatorId).get(boURI);
							StringMap existingVolumesInMask = mask.getExistingVolumes();
							if (existingVolumesInMask != null &&
									existingVolumesInMask.containsValue(requestedHLU.toString())) {
								ExportOrchestrationTask completer = new ExportOrchestrationTask(
										exportGroup.getId(), token);
								ServiceError serviceError =
										DeviceControllerException.errors.
										exportHasExistingVolumeWithRequestedHLU(boURI.toString(), requestedHLU.toString());
								completer.error(_dbClient, serviceError);
								return false;
							}

                            String volumePolicyName = ControllerUtils.getAutoTieringPolicyName(bo.getId(), _dbClient);              
                            if (volumePolicyName.equalsIgnoreCase(Constants.NONE.toString())) {
                                volumePolicyName = null;
                            }
                            
                            VirtualPool virtualPool = null;
                            if ( bo instanceof Volume) {
    							Volume volume = (Volume)bo;
    			                virtualPool = uriVirtualPoolMap.get(volume.getVirtualPool());
    			                if ( virtualPool == null) {
    			                    virtualPool = _dbClient.queryObject(VirtualPool.class, volume.getVirtualPool());
    			                    uriVirtualPoolMap.put(volume.getVirtualPool(), virtualPool);
    			                }
                            }
			                

							// There is no policy in the mask and volume: that's an exact match.
							// There is a policy in the mask and it's exactly the only policy for this mask.
							// Exact is either a simple mask with our policy, or a non-simple mask with our policy and only our policy
							// The mask must contain all the initiators associated with the compute resource.
							if (rule == 1) {
								// Spot-check partial masks before continuing:  Either...
								// 1. This mask is a full mask (no partial initiator masks that only make up a portion of the whole compute resource)
								// 2. This mask is a a partial mask, but all of the masks point to the same SG (which works for both FAST and non-FAST)
								// 3. The volume is NON-FAST
								if (!partialMasks.contains(mask.getId()) || partialMasksContainSameSG(partialMasks, masks, mask) || volumePolicyName == null) {
									// Exact fit case, no FAST policy
									if (volumePolicyName == null && policy.localTierPolicy == null) {
										_log.info("Pre-existing Mask Matched rule 1B: volume and mask do not have FAST policy");
										match = true;
									}

									// Exact fit case, FAST policy with non-cascading storage group
									if (volumePolicyName != null) {
										if (policy.localTierPolicy != null) {
										    if(isVMAX3){
										        match = SmisUtils.checkPolicyMatchForVMAX3(policy.localTierPolicy, volumePolicyName);
										    } else {
										        match = policy.localTierPolicy.equalsIgnoreCase(volumePolicyName);
										    }
										    if(match)
										        _log.info("Pre-existing Mask Matched rule 1C: volume has same FAST policy as masking view with non-cascading storage group");
										
										}

										// Exact fit case, FAST policy with cascading storage group, but there's only one FAST policy, and it's ours.
										if (policy.localTierPolicy == null && policy.tierPolicies != null && policy.tierPolicies.size() == 1) {
										    if(isVMAX3) {
										        String policyName = policy.tierPolicies.iterator().next();
										        match = SmisUtils.checkPolicyMatchForVMAX3(policyName, volumePolicyName);
										    } else {
										        match = policy.tierPolicies.contains(volumePolicyName);
										    }
										    
										    if(match)
										        _log.info("Pre-existing Mask Matched rule 1D: volume has same FAST policy as masking view with cascading storage group");
										}									
									}
									
									// verify host io limits match if policy name is a match
									if (virtualPool != null) {
    									match &= HostIOLimitsParam.isEqualsLimit(policy.getHostIOLimitBandwidth(), virtualPool.getHostIOLimitBandwidth())
    			                                && HostIOLimitsParam.isEqualsLimit(policy.getHostIOLimitIOPs(),virtualPool.getHostIOLimitIOPs());
									}
								} else {
									_log.info("Pre-existing Mask did not match rule 1A: volume is FAST, mask comprises only part of the compute resource, and the storage groups in each mask are not the same.  " + 
								              "Attempting to use this mask would cause a violation on the VMAX since the same volume can not be in more than one storage group with a FAST policy defined.");
								}
							}

							// The mask is associated with at least more than one policy (including non-FAST)
							// and it's using cascading storage groups.
							if (rule == 2) {
							    // if it is a cascaded SG, mask need to be selected
								if (!policy.simpleMask) {
                                    _log.info("Pre-existing mask Matched rule 2A: volume has FAST policy and masking view has cascaded storage group");
                                    // No need to check for phantom SGs for VMAX3
                                    // Host IO limits cannot be associated to
                                    // phantom SGs, hence verify if IO limit set on the SG within MV if not we need to create a new Masking view.
                                    if (!isVMAX3 && ExportMaskPolicy.EXPORT_TYPE.PHANTOM.name().equalsIgnoreCase(policy.getExportType())) {
                                        if (virtualPool != null) {
                                            if (HostIOLimitsParam.isEqualsLimit(policy.getHostIOLimitBandwidth(), virtualPool.getHostIOLimitBandwidth())
                                                    && HostIOLimitsParam.isEqualsLimit(policy.getHostIOLimitIOPs(), virtualPool.getHostIOLimitIOPs())) {
                                                _log.info("Pre-existing mask Matched rule 2A-1: Phantom SGs are available to add FAST volumes to this masking view, and expected HostIO limit is set on SG within masking view.");
                                                match = true;
                                            } else {
                                                _log.info("Pre-existing mask did not match rule 2A-1: Phantom SGs are available to add FAST volumes to this masking view, but HostIO limit is not set on SG within masking view.");
                                            }
                                        }
                                    } else {
                                        match = true;
                                    }
                                   
								} else {
									if (volumePolicyName == null) {
										_log.info("Pre-existing mask did not match rule 2A: volume does not have a FAST policy, and this rules requires the volume to have a FAST policy associated with it");
									}
									if (policy.simpleMask) {
										_log.info("Pre-existing mask did not match rule 2A: mask has a cascaded storage group, and this rule requires the storage group be non-cascaded in the mask");
									}
								}
							}

							// If it's a non-cascaded SG, non-FAST masking view with at least 1 fast volume in it, then we can select it because 
							// we're capable of creating phantom storage groups.
							if (!isVMAX3 && rule == 3) {
								if (volumePolicyName != null) {
									if ((policy.tierPolicies == null || policy.tierPolicies.isEmpty()) && policy.simpleMask) {
										_log.info("Pre-existing mask Matched rule 3A: volume has non-cascaded, non-FAST storage group, allowing VipR to make/use island storage groups for FAST");
	                                    match = true;
	                                    
                                        // verify host io limits match if policy name is a match
	                                    if ( virtualPool != null) {
    	                                    match = HostIOLimitsParam.isEqualsLimit(policy.getHostIOLimitBandwidth(), virtualPool.getHostIOLimitBandwidth())
    	                                            && HostIOLimitsParam.isEqualsLimit(policy.getHostIOLimitIOPs(),virtualPool.getHostIOLimitIOPs());
	                                    }
									} else {
										_log.info("Pre-existing mask did not match rule 3A: volume is FAST and mask does not have a non-cascaded, non-FAST storage group.  A non-cascaded, non-FAST storage group in the masking view allows ViPR to " +
												  "create or use a separate island storage group for FAST volumes");
									}
								} else {
									_log.info("Pre-existing mask did not match rule 3A: volume does not have a FAST policy, and this rule requires the volume to have a FAST policy associated with it");
								}
							}

							if (match) {
								_log.info(String.format("Found that we can add volume %s to export mask %s", bo.getLabel(), mask.getMaskName()));
								// The volume doesn't exist, so we have to add it to
								// the masking container.
								Map<URI, Integer> newVolumes = existingMasksToUpdateWithNewVolumes
										.get(mask.getId());
								if (newVolumes == null) {
									newVolumes = new HashMap<URI, Integer>();
									existingMasksToUpdateWithNewVolumes.put(mask.getId(), newVolumes);
								}

								// Check to see if the volume is already in this mask.  (Map hashcode not finding existing volume URIs)
								if (!newVolumes.containsKey(bo.getId())) {
									newVolumes.put(bo.getId(), requestedHLU);
									mask.addToUserCreatedVolumes(bo);
								} else {
									_log.info(String.format("Found we already have volume %s in the list for mask %s", bo.getLabel(), mask.getMaskName()));
								}
							}
						}

						if (match) {
							// We found a mask for this volume, remove from the no-mask-yet list
							volumesWithMask.put(initiatorId, boURI);
						}

					} else if (mask.hasExistingVolume(bo)) {
						// We found a mask for this volume, remove from the no-mask-yet list
						_log.info(String.format("rule %d: according to the database, volume %s is already in the mask: %s", rule, bo.getWWN(),
								mask.getMaskName()));
						volumesWithMask.put(initiatorId, boURI);
					}
				}
			}

		    // Update the list of volumes and initiators for the mask
		    Map<URI, Integer> volumeMapForExistingMask = existingMasksToUpdateWithNewVolumes
		            .get(mask.getId());
		    if (volumeMapForExistingMask != null && !volumeMapForExistingMask.isEmpty()) {
		        mask.addVolumes(volumeMapForExistingMask);
		    }

		    // Remove the entries from the no-mask-yet map
			for (Entry<URI, Collection<URI>> entry : volumesWithMask.asMap().entrySet()) {
				URI initiatorId = entry.getKey();
				if (volumesWithNoMask != null &&
					volumesWithNoMask.get(initiatorId) != null) {
					for (URI boId : entry.getValue()) {
						if (volumesWithNoMask.get(initiatorId) != null) {
							volumesWithNoMask.get(initiatorId).remove(boId);
							if (volumesWithNoMask.get(initiatorId).isEmpty()) {
								volumesWithNoMask.remove(initiatorId);
							}
						}
					}
				}				
			}
		}

		return true;
	}

    /**
     * Determines if the mask has the same SG as the other masks that are partial masks.
     * 
     * @param partialMasks list of export masks that are partial masks
     * @param masks 
     * @param mask export mask
     * @return true if all masks in partialMasks have the same SG
     */
    private boolean partialMasksContainSameSG(Set<URI> partialMasks, Map<ExportMask, ExportMaskPolicy> masks, ExportMask mask) {
		String sgName = null;
		// Find the mask in the mask mapping, grab the SG name
		for (Map.Entry<ExportMask, ExportMaskPolicy> entry : masks.entrySet()) {
			ExportMaskPolicy policy = entry.getValue();
			sgName = policy.sgName; 
		}
    	
    	for (URI partialMaskURI : partialMasks) {
    		// Find the mask in the mask mapping
    		for (Map.Entry<ExportMask, ExportMaskPolicy> entry : masks.entrySet()) {
    			ExportMask myMask = entry.getKey();
    			if (myMask.getId().equals(partialMaskURI)) {
    				ExportMaskPolicy policy = entry.getValue();
    				if (sgName == null || !sgName.equalsIgnoreCase(policy.sgName)) {
    					return false;
    				}
    			}
    		}
    	}
		return true;
	}

	/**
     * This method will search the array for existing exports that match the set of
     * compute to initiator port names map.
     *
     * For those cases where exportGroup.Type = Cluster or Host,
     * this will attempt make sure that existing exports with exactly those initiators
     * a compute are considered hits.
     *
     * @param device         [in] - BlockStorageDevice interface for accessing find
     *                       function for VMAX
     *
     * @param storage        [in] - StorageSystem object representing the physical
     *                       array that we are going to search
     *
     * @param exportGroup    [in] - ExportGroup object representing the ViPR level export
     * @param computeToPorts [in] - Multi list (basically,
     *                       a map of String to Collection of Strings),
     *                       representing the list of initiator port names for compute
     *                       resources (clusters or hosts)
     * @return Map of compute resources keys to set of ExportMask URIs.
     */
    protected Map<String, Set<URI>> findExistingMasksForComputeResources(BlockStorageDevice device, StorageSystem storage,
    		                                                             ExportGroup exportGroup,
    		                                                             ListMultimap<String, String> computeToPorts) {
    	Map<String, Set<URI>> matchingExportMaskURIs = new HashMap<String, Set<URI>>();
    	// Loop all compute resources and look up existing masks (if any) and to the
    	// the result list.
    	for (Map.Entry<String, Collection<String>> entry :
    		computeToPorts.asMap().entrySet()) {
    		String computeResourceId = entry.getKey();
    		List<String> portNames = new ArrayList<String>(entry.getValue());
    		_log.info("findExistingMasksForComputeResource - Trying to find " +
    				"existing export for compute resource {} with these ports: {}",
    				computeResourceId, Joiner.on(',').join(portNames));
    		Map<String, Set<URI>> exportMaskURIs =
    				device.findExportMasks(storage, portNames, false);
    		for (String portName : exportMaskURIs.keySet()) {
    			if (exportMaskURIs.get(portName) != null) {
    				for (URI maskURI : exportMaskURIs.get(portName)) {
    					ExportMask mask = _dbClient.queryObject(ExportMask.class, maskURI);
    					boolean addMask = true;
    					if (exportGroup.forHost() && maskAppliesToMultipleHosts(mask)) {
    						addMask = false;
    						_log.info("findExistingMasksForComputeResource - disqualifying mask {} because it contains multiple hosts", mask.getMaskName());
    					} else if (exportGroup.forCluster() && !maskAppliesToMultipleHosts(mask)) {
    						addMask = false;
    						_log.info("findExistingMasksForComputeResource - (temporarily) disqualifying mask {} because it does not " +
    								"contain multiple hosts.  Additional check will be made in next phase.", mask.getMaskName());
    					}

    					if (addMask) {
    						if (matchingExportMaskURIs.get(computeResourceId) == null) {
    							matchingExportMaskURIs.put(computeResourceId, new HashSet<URI>());
    						}
    						matchingExportMaskURIs.get(computeResourceId).add(maskURI);
    					}
    				}
    			}
    		}

    		// Did we find any exact matches for this compute resource for cluster?  If so, we're done looking.
    		// Otherwise we're going to look for an aggregate of multiple masking views that, when combined,
    		// equal the whole cluster.
    		if (exportGroup.getType() != null && exportGroup.forCluster() && matchingExportMaskURIs.isEmpty()) {
    			_log.info("findExistingMasksForComputeResource - Trying to find " +
    					"existing multiple export for compute resource {} with these exact ports: {}",
    					computeResourceId, Joiner.on(',').join(portNames));
    			// Refresh the export masks to find subsets of our ports
    			// TODO: somehow only call findExportMasks once.
    			exportMaskURIs =
    					device.findExportMasks(storage, portNames, false);
    			if (exportMaskURIs.size() == portNames.size()) {
    				_log.info("findExistingMasksForComputeResource - Found that returned masks do contain " +
    						"all of the port necessary to consistute the compute resource: " + computeResourceId);
    				for (String portName : exportMaskURIs.keySet()) {
    					if (matchingExportMaskURIs.get(computeResourceId) == null) {
    						matchingExportMaskURIs.put(computeResourceId, new HashSet<URI>());
    					}
    					matchingExportMaskURIs.get(computeResourceId).addAll(exportMaskURIs.get(portName));
    				}
    			}
    		}
    	}
    	_log.info("findExistingMasksForComputeResource - {} compute resources were found",
    			matchingExportMaskURIs.size());
    	return matchingExportMaskURIs;
    }

    @Override
    public String checkForSnapshotsToCopyToTarget(Workflow workflow,
            StorageSystem storage, String previousStep, Map<URI, Integer> volumeMap,
            Collection<Map<URI, Integer>> values) {
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * This function processes the initiatorURIs and return a mapping of String
     * host or cluster resource reference to a list Initiator URIs.
     *
     * This is the default implementation and it will group the
     * initiator's host reference
     *
     * @param exportGroup   [in] - ExportGroup object to examine
     * @param initiatorURIs [in] - Initiator URIs
     * @return Map of String:computeResourceName to List of Initiator URIs
     */
   @Override
   protected Map<String, List<URI>> mapInitiatorsToComputeResource(
           ExportGroup exportGroup, Collection<URI> initiatorURIs) {
       Map<String, List<URI>> result = new HashMap<String, List<URI>>();
       if (exportGroup.forCluster()) {
           Cluster singleCluster = null;
           if (exportGroup.getClusters() != null && exportGroup.getClusters().size() == 1) {
               String clusterUriString = exportGroup.getClusters().iterator().next();
               singleCluster = _dbClient.queryObject(Cluster.class, URI.create(clusterUriString));
           }
           for (URI newExportMaskInitiator : initiatorURIs) {
               Initiator initiator =
                       _dbClient.queryObject(Initiator.class,
                               newExportMaskInitiator);
               String clusterName = getClusterName(singleCluster, initiator);
               List<URI> initiatorSet = result.get(clusterName);
               if (initiatorSet == null) {
                   initiatorSet = new ArrayList<URI>();
                   result.put(clusterName, initiatorSet);
               }
               initiatorSet.add(newExportMaskInitiator);
               _log.info(String.format("cluster = %s, initiators to add to map: %s, ",
                       clusterName,
                       newExportMaskInitiator.toString()));
           }
       } else {
           // Bogus URI for those initiators without a host object, helps maintain a good map.
           // We want to put bunch up the non-host initiators together.
           URI fillerHostURI = NullColumnValueGetter.getNullURI();
           for (URI newExportMaskInitiator : initiatorURIs) {
               Initiator initiator = _dbClient.queryObject(Initiator.class,
                       newExportMaskInitiator);

               // Not all initiators have hosts, be sure to handle either case.
               URI hostURI = initiator.getHost();
               if (hostURI == null) {
                   hostURI = fillerHostURI;
               }

               List<URI> initiatorSet = result.get(hostURI.toString());
               if (initiatorSet == null) {
                   initiatorSet = new ArrayList<URI>();
                   result.put(hostURI.toString(), initiatorSet);
               }
               initiatorSet.add(initiator.getId());

               _log.info(String.format("host = %s, initiators to add to map: %d, ",
                       hostURI,
                       result.get(hostURI.toString()).size()));
           }
       }
       return result;
   }

    @Override
    public void exportGroupChangePolicyAndLimits(URI storageURI, URI exportMaskURI,
            URI exportGroupURI, List<URI> volumeURIs, URI newVpoolURI,
            boolean rollback, String token) throws Exception {

        ExportOrchestrationTask taskCompleter = new ExportOrchestrationTask(
                exportGroupURI, token);
        ExportMask exportMask = _dbClient.queryObject(ExportMask.class, exportMaskURI);
        StorageSystem storage = _dbClient.queryObject(StorageSystem.class, storageURI);
        VirtualPool newVpool = _dbClient.queryObject(VirtualPool.class, newVpoolURI);
        BlockStorageDevice device = getDevice();
        device.updatePolicyAndLimits(storage, exportMask, volumeURIs, newVpool,
                rollback, taskCompleter);
    }
    
    @Override
    public void changeAutoTieringPolicy(URI storageURI, List<URI> volumeURIs,
            URI newVpoolURI, boolean rollback, String token) throws Exception {

        VolumeUpdateCompleter taskCompleter = new VolumeUpdateCompleter(volumeURIs, token);
        StorageSystem storage = _dbClient.queryObject(StorageSystem.class, storageURI);
        VirtualPool newVpool = _dbClient.queryObject(VirtualPool.class, newVpoolURI);
        BlockStorageDevice device = getDevice();
        device.updatePolicyAndLimits(storage, null, volumeURIs, newVpool,
                rollback, taskCompleter);
    }

    /**
     * Determine the name of the cluster that the initiator belongs to or belonged to. It is possible that
     * the cluster to host relationship is altered prior to the export operation. Hence, the initiator.clusterName
     * may be null or empty. So, we need to account for this case. We can determine the cluster name by
     * other means only when the ExportGroup contains a single cluster.
     *
     * @param singleCluster [in] - Cluster object. Can be null if ExportGroup does not have a single cluster in it
     * @param initiator     [in] - Initiator object.
     * @return Cluster name that the initiator belongs (or belonged to)
     */
    private String getClusterName(Cluster singleCluster, Initiator initiator) {
        String initiatorClusterName = initiator.getClusterName();
        if (Strings.isNullOrEmpty(initiatorClusterName) && singleCluster != null) {
            // clusterName is unknown and singleCluster is non-null meaning that the
            // initiator should be associated with that cluster, so use that as the
            /// cluster name
            initiatorClusterName = singleCluster.getLabel();
}
        return initiatorClusterName;
    }
}
