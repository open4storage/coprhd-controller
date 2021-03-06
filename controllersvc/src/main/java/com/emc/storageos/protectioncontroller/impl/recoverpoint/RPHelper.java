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

import static com.emc.storageos.db.client.constraint.ContainmentConstraint.Factory.getVolumesByConsistencyGroup;
import static com.emc.storageos.db.client.constraint.ContainmentConstraint.Factory.getRpJournalVolumeParent;
import static com.emc.storageos.db.client.constraint.ContainmentConstraint.Factory.getSecondaryRpJournalVolumeParent;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.blockorchestrationcontroller.VolumeDescriptor;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.constraint.ContainmentConstraint;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.BlockConsistencyGroup;
import com.emc.storageos.db.client.model.Network;
import com.emc.storageos.db.client.model.ProtectionSet;
import com.emc.storageos.db.client.model.ProtectionSystem;
import com.emc.storageos.db.client.model.StoragePool;
import com.emc.storageos.db.client.model.StoragePort;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringMap;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.VirtualArray;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.Volume.PersonalityTypes;
import com.emc.storageos.db.client.model.VpoolProtectionVarraySettings;
import com.emc.storageos.db.client.util.CustomQueryUtility;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.db.client.util.SizeUtil;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.exceptions.DeviceControllerExceptions;
import com.emc.storageos.recoverpoint.exceptions.RecoverPointException;
import com.emc.storageos.recoverpoint.impl.RecoverPointClient;
import com.emc.storageos.recoverpoint.utils.RecoverPointClientFactory;
import com.emc.storageos.svcs.errorhandling.resources.InternalException;
import com.emc.storageos.util.ConnectivityUtil;
import com.emc.storageos.util.NetworkLite;
import com.emc.storageos.util.NetworkUtil;
import com.emc.storageos.volumecontroller.impl.smis.MetaVolumeRecommendation;
import com.emc.storageos.volumecontroller.impl.utils.MetaVolumeUtils;
import com.google.common.base.Joiner;

import static com.emc.storageos.db.client.constraint.AlternateIdConstraint.Factory.getVolumesByAssociatedId;
import static com.emc.storageos.db.client.constraint.AlternateIdConstraint.Factory.getRpSourceVolumeByTarget;

/**
 * RecoverPoint specific helper bean
 */
public class RPHelper {

    private static final double RP_DEFAULT_JOURNAL_POLICY = 0.25;
	public static final String REMOTE = "remote";
    public static final String LOCAL = "local";
    public static final String SOURCE = "source";
    public static final Long DEFAULT_RP_JOURNAL_SIZE_IN_BYTES = 10737418240L; //default minimum journal size is 10GB (in bytes) 

    private DbClient            _dbClient;
    private static final Logger _log = LoggerFactory.getLogger(RPHelper.class);
        
    private static final String HTTPS = "https";
    private static final String WSDL = "wsdl";
    private static final String RP_ENDPOINT = "/fapi/version4_1";

    public void setDbClient(DbClient dbClient) {
        _dbClient = dbClient;
    }

    /**
     * Get all of the volumes in this replication set; the source and all of its targets.
     * For a multi-CG protection, it only returns the targets (and source) associated with this one volume.
     *
     * @param volume volume object
     * @return list of volume URIs
     * @throws DeviceControllerException
     */
    public List<URI> getReplicationSetVolumes(Volume volume) throws DeviceControllerException {

    	if (volume == null) {
            throw DeviceControllerException.exceptions.invalidObjectNull();
    	}

        List<URI> volumeIDs = new ArrayList<URI>();
        volumeIDs.add(volume.getId());

        if (volume.getRpTargets() != null) {
        	for (String volumeId : volume.getRpTargets()) {
        		try {
					volumeIDs.add(URI.create(volumeId));
				} catch (IllegalArgumentException e) {
                    throw DeviceControllerException.exceptions.invalidURI(e);
				}
        	}
        }

        return volumeIDs;
    }

    /**
     * Helper Method: The caller wants to get the protection settings associated with a specific virtual array
     * and virtual pool. Handle the exceptions appropriately.
     *
     * @param vpool VirtualPool to look for
     * @param varray VirtualArray to protect to
     * @return the stored protection settings object
     * @throws InternalException 
     */
    public VpoolProtectionVarraySettings getProtectionSettings(VirtualPool vpool, VirtualArray varray) throws InternalException {
        if (vpool.getProtectionVarraySettings() != null) {
            String settingsID = vpool.getProtectionVarraySettings().get(varray.getId().toString());
            try {
                return (_dbClient.queryObject(VpoolProtectionVarraySettings.class, URI.create(settingsID)));
            } catch (IllegalArgumentException e) {
                throw DeviceControllerException.exceptions.invalidURI(e);
            }
        }
        throw DeviceControllerException.exceptions.objectNotFound(varray.getId());
    }

    /**
     * This method will return all volumes that should be deleted based on this one volume.
     * If this is the last source volume in the CG, this method will return all journal volumes as well.
     *
     * @param volume volume ID
     * @param reqDeleteVolumes all volumes in the delete request
     * @return list of volumes to unexport and delete
     * @throws InternalException
     * @throws URISyntaxException 
     */
    public List<URI> getVolumesToDelete(Volume volume, Collection<URI> reqDeleteVolumes) throws InternalException {
    	if (volume == null) {
            throw DeviceControllerException.exceptions.invalidObjectNull();
    	}

        List<URI> volumeIDs = new ArrayList<URI>();
    	boolean wholeCG = true;
    	if (volume.getProtectionSet() == null) {
    		_log.error("We could not figure out the other protected volumes associated with this request.  Only deleting this volume, others will need to be deleted manually as well.");
    		volumeIDs.add(volume.getId());
    		return volumeIDs;
    	}
    	
        ProtectionSet protectionSet = _dbClient.queryObject(ProtectionSet.class, volume.getProtectionSet());
        
        List<URI> volumesToDelete = new ArrayList<URI>();
        List<String> staleVolumes = new ArrayList<String>();
        
        for (String volumeString : protectionSet.getVolumes()) {
            URI volumeURI;
            try {
                volumeURI = URI.create(volumeString);
                // First check to see if this volume exists in the DB.  The ProtectionSet may hold
                // a stale volume reference.
                Volume vol = _dbClient.queryObject(Volume.class, volumeURI);
                if (vol != null && !vol.getInactive()) {
                    // Volume reference exists so add the volume.
                    volumeIDs.add(volumeURI);
                } else {
                    // The ProtectionSet references a stale volume that no longer exists in the DB.
                    _log.info("ProtectionSet " + protectionSet.getLabel() + " references volume " + volumeURI.toString() + " that no longer exists in the DB.  Removing this volume reference.");
                    staleVolumes.add(volumeString);
                }
            } catch (IllegalArgumentException e) {
                _log.error("URI syntax incorrect: " + e, e);
            }
        }

        // If we have removed stale volume references from the ProtectionSet, we need
        // to update and persist.
        if (!staleVolumes.isEmpty()) {
            for (String vol : staleVolumes){
                protectionSet.getVolumes().remove(vol);
            }
            _dbClient.persistObject(protectionSet);
        }
        
        if (!containsAllRPSourceVolumes(_dbClient, protectionSet, reqDeleteVolumes)) {
        	_log.info("We are not going to remove the entire CG from the export group");
        	wholeCG = false;
        }

        if (!wholeCG) {
            Iterator<URI> iter = volumeIDs.iterator();
            while (iter.hasNext()) {
                URI protectedVolumeID = iter.next();
                Volume protectionVolume = _dbClient.queryObject(Volume.class, protectedVolumeID);
                if (protectionVolume != null) {
                    if (!protectionVolume.getPersonality().equals(Volume.PersonalityTypes.METADATA.toString())
                            && protectionVolume.getRSetName() != null 
                            && protectionVolume.getRSetName().equals(volume.getRSetName())) {
                        // Add volumes belonging to the replication set of the source volume.
                        volumesToDelete.add(protectionVolume.getId());                        
                    }
                }
            }
            
            // Based on the list of volumes passed in, determine all the journals that qualify to be removed as part
            // of this delete operation.           
            volumesToDelete.addAll(determineJournalsToRemove(protectionSet, volumesToDelete));			
        } else {
            // Deleting the whole CG so all protection set volumes to be removed.
            volumesToDelete.addAll(volumeIDs);
        } 
        return volumesToDelete;
    }

    private int getJournalRsetCount(List<URI> protectionSetVolumes, URI journalVolume) {
        int rSetCount = 0;
        
        Iterator<URI> iter = protectionSetVolumes.iterator();
        while (iter.hasNext()) {
            URI protectedVolumeID = iter.next();
            Volume protectionVolume = _dbClient.queryObject(Volume.class, protectedVolumeID);
            if (!protectionVolume.getInactive() && 
            		!protectionVolume.getPersonality().equals(Volume.PersonalityTypes.METADATA.toString())
                    && protectionVolume.getRpJournalVolume().equals(journalVolume)) {
                rSetCount++;
            }
        }
        
        return rSetCount;
    }
    
	/**
	 * Determine if the protection set's source volumes are represented in the volumeIDs list.
	 * Used to figure out if we can perform full CG operations or just partial CG operations.
	 * 
	 * @param dbClient db client
	 * @param protectionSet protection set
	 * @param volumeIDs volume IDs
	 * @return true if volumeIDs contains all of the source volumes in the protection set
	 */
	public static boolean containsAllRPSourceVolumes(DbClient dbClient, ProtectionSet protectionSet, Collection<URI> volumeIDs) {
		
		// find all source volumes.
		List<URI> sourceVolumeIDs = new ArrayList<URI>();
		_log.info("Inspecting protection set: " + protectionSet.getLabel() + " to see if request contains all source volumes");
		for (String volumeIDStr : protectionSet.getVolumes()) {
			Volume volume = dbClient.queryObject(Volume.class, URI.create(volumeIDStr));
			if (volume != null) {
				_log.debug("Looking at volume: " + volume.getLabel());
				if (!volume.getInactive() && volume.getPersonality().equals(Volume.PersonalityTypes.SOURCE.toString())) {
					_log.debug("Adding volume: " + volume.getLabel());
					sourceVolumeIDs.add(volume.getId());
				}
			}
		}
		
		// go through all volumes sent in, remove any volumes you find in the source list.
		sourceVolumeIDs.removeAll(volumeIDs);
		
		if (!sourceVolumeIDs.isEmpty()) {
			_log.info("Found that the volumes requested do not contain all source volumes in the protection set, namely: " + 
					Joiner.on(',').join(sourceVolumeIDs));
			return false;
		}

		_log.info("Found that all of the source volumes in the protection set are in the request.");
		return true;
	}

	/**
     * Determines if a journal volume is shared by multiple replication sets.
     * 
     * @param protectionSetVolumes volumes from a protection set
     * @param journalVolume journal volume
     * @return true if journal is shared between more than one volume in a protection set
     */
    public boolean isJournalShared(List<URI> protectionSetVolumes, URI journalVolume) {
    	if (getJournalRsetCount(protectionSetVolumes, journalVolume) > 1) {
    		return true;
        }
        
        return false;
    }

    /**
     * Determines if a journal volume is active in a list of volumes.  
     * 
     * @param protectionSetVolumes volumes from a protection set
     * @param journalVolume journal volume
     * @return true if journal is active with any active volume in a protection set
     */
    public boolean isJournalActive(List<URI> protectionSetVolumes, URI journalVolume) {
    	if (getJournalRsetCount(protectionSetVolumes, journalVolume) > 0) {
    		return true;
        }
        
        return false;
    }

	/**
	 * Given an RP source volume and a protection virtual array, give me the corresponding target volume.
	 * 
	 * @param id source volume id 
	 * @param virtualArray virtual array protected to
	 * @return Volume of the target
	 */
	public static Volume getRPTargetVolumeFromSource(DbClient dbClient, Volume srcVolume, URI virtualArray) {
		if (srcVolume.getRpTargets() == null || srcVolume.getRpTargets().isEmpty()) {
			return null;
		}
		
		for (String targetId : srcVolume.getRpTargets()) {
			Volume target = dbClient.queryObject(Volume.class, URI.create(targetId));
			
			if (target.getVirtualArray().equals(virtualArray)) {
				return target;
			}
		}
		
		return null;
	}

    /**
     * Given a RP target volume, this method gets the corresponding source volume.
     * 
     * @param dbClient the database client.
     * @param id target volume id.
     */
    public static Volume getRPSourceVolumeFromTarget(DbClient dbClient, Volume tgtVolume) {
        Volume sourceVolume = null;
        
        if (tgtVolume == null) {
            return sourceVolume;
        }
        
        final List<Volume> sourceVolumes = CustomQueryUtility
                .queryActiveResourcesByConstraint(dbClient, Volume.class,
                 getRpSourceVolumeByTarget(tgtVolume.getId().toString()));
        
        if (sourceVolumes != null && !sourceVolumes.isEmpty()) {
            // A RP target volume will only be associated to 1 source volume so return
            // the first entry.
            sourceVolume = sourceVolumes.get(0);
        }
        
        return sourceVolume;
    }	
	
    /**
     * Given a RP journal volume, this method gets the corresponding parent volume.  The
     * parent will either be a source or target volume.
     * 
     * @param dbClient the database client.
     * @param id target volume id.
     */
    public static Volume getRPJournalParentVolume(DbClient dbClient, Volume journalVolume) {
        // Source or target parent volume.
        Volume parentVolume = null;
        
        if (journalVolume == null) {
            return parentVolume;
        }
        
        List<Volume> parentVolumes = CustomQueryUtility
                .queryActiveResourcesByConstraint(dbClient, Volume.class,
                        getRpJournalVolumeParent(journalVolume.getId())); 
        
        // If we haven't found a primary journal volume parent then this volume might be
        // a secondary journal volume.  So try to find a secondary journal volume parent.
        if (parentVolumes == null || parentVolumes.isEmpty()) {
            parentVolumes = CustomQueryUtility
                    .queryActiveResourcesByConstraint(dbClient, Volume.class,
                            getSecondaryRpJournalVolumeParent(journalVolume.getId())); 
        }
        
        if (parentVolumes != null && !parentVolumes.isEmpty()) {
            // A RP journal volume will only be associated to 1 source or target volume so return
            // the first entry.
            parentVolume = parentVolumes.get(0);
        }
        
        return parentVolume;
    }
    
    /**
     * Gets the associated source volume given any type of RP volume.  If a source volume
     * is given, that volume is returned.  For a source journal volume, the associated source 
     * volume is found and returned.  For a target journal volume, the associated target
     * volume is found and then its source volume is found and returned.  For a target volume,
     * the associated source volume is found and returned.
     * 
     * @param dbClient the database client.
     * @param volume the volume for which we find the associated source volume.
     * @return the associated source volume.
     */
    public static Volume getRPSourceVolume(DbClient dbClient, Volume volume) {
        Volume sourceVolume = null;
        
        if (volume == null) {
            return sourceVolume;
        }

        if (NullColumnValueGetter.isNotNullValue(volume.getPersonality())) {
            if (volume.getPersonality().equals(PersonalityTypes.SOURCE.name())) {
                _log.info("Attempting to find RP source volume corresponding to source volume " + volume.getId());
                sourceVolume = volume;
            } else if (volume.getPersonality().equals(PersonalityTypes.TARGET.name())) {
                _log.info("Attempting to find RP source volume corresponding to target volume " + volume.getId());
                sourceVolume = getRPSourceVolumeFromTarget(dbClient, volume);
            } else if (volume.getPersonality().equals(PersonalityTypes.METADATA.name())) {
                _log.info("Attempting to find RP source volume corresponding to journal volume" + volume.getId());
                Volume journalParent = getRPJournalParentVolume(dbClient, volume);
                // The journal's parent might be a target volume.  In this case we want
                // to get the associated source.
                if (journalParent.getPersonality().equals(PersonalityTypes.TARGET.name())) {
                    sourceVolume = getRPSourceVolumeFromTarget(dbClient, journalParent);
                } else {
                    // The journal's parent is in fact the source volume.
                    sourceVolume = journalParent;
                }
            } else {
                _log.warn("Attempting to find RP source volume corresponding to an unknown RP volume type, for volume " + volume.getId());
            }
        }
        
        if (sourceVolume == null) {
            _log.warn("Unable to find RP source volume corresponding to volume " + volume.getId());
        } else {
            _log.info("Found RP source volume " + sourceVolume.getId() + ", corresponding to volume " + volume.getId());
        }
        
        return sourceVolume;
    }
    
	/** 
	 * Convenience method that determines if the passed network is connected to the 
	 * passed varray.
	 * 
	 * Check the assigned varrays list if it exist, if not check against the connect varrays.
	 * @param network
	 * @param virtualArray
	 * @return
	 */
	public boolean isNetworkConnectedToVarray(NetworkLite network, VirtualArray virtualArray) {    
		if (network != null && network.getConnectedVirtualArrays() != null && network.getConnectedVirtualArrays().contains(String.valueOf(virtualArray.getId()))) {
			return true;
		}		
		return false;
	}
	
	/**
     * Check if initiator being added to export-group is good.
     *
     * @param exportGroup
     * @param initiator
     * @throws InternalException
     */
    public boolean isInitiatorInVarray(VirtualArray varray, String wwn) throws InternalException {
        // Get the networks assigned to the virtual array.
        List<Network> networks = CustomQueryUtility.queryActiveResourcesByRelation(
            _dbClient, varray.getId(), Network.class, "connectedVirtualArrays");
                      
        for (Network network : networks) {
            if (network == null || network.getInactive() == true) {
                continue;
            }
            
            StringMap endpointMap = network.getEndpointsMap();
            for (String endpointKey : endpointMap.keySet()) {
                String endpointValue = endpointMap.get(endpointKey);
                if (wwn.equals(endpointValue) || 
                    wwn.equals(endpointKey)) {
                    return true;
                }
            }
        }        
        
        return false;
    }
 
    /**
     * Check if any of the networks containing the RP site initiators contains storage
     * ports that are explicitly assigned or implicitly connected to the passed virtual
     * array.
     *
     * @param storageSystemURI The storage system who's connected networks we want to find.
     * @param protectionSystemURI The protection system used to find the site initiators.
     * @param siteId The side id for which we need to lookup associated initiators.
     * @param varrayURI The virtual array being used to check for network connectivity
     * @throws InternalException
     */
    public boolean rpInitiatorsInStorageConnectedNework(URI storageSystemURI, URI protectionSystemURI, String siteId, URI varrayURI) throws InternalException {
        // Determine what network the StorageSystem is part of and verify that the RP site initiators
        // are part of that network.
        // Then get the front end ports on the Storage array.
        Map<URI, List<StoragePort>> arrayTargetMap = ConnectivityUtil.getStoragePortsOfType(_dbClient, 
                storageSystemURI, StoragePort.PortType.frontend);
        Set<URI> arrayTargetNetworks = new HashSet<URI>();
        arrayTargetNetworks.addAll(arrayTargetMap.keySet());
        
        ProtectionSystem protectionSystem = 
                _dbClient.queryObject(ProtectionSystem.class, protectionSystemURI);
        StringSet siteInitiators = 
                protectionSystem.getSiteInitiators().get(siteId);
        
        // Build a List of RP site initiator networks
        Set<URI> rpSiteInitiatorNetworks = new HashSet<URI>();
        for (String wwn : siteInitiators) {
            NetworkLite rpSiteInitiatorNetwork = NetworkUtil.getEndpointNetworkLite(wwn, _dbClient);
            if (rpSiteInitiatorNetwork != null) {
                rpSiteInitiatorNetworks.add(rpSiteInitiatorNetwork.getId());
            }
        }
        
        // Eliminate any storage ports that are not explicitly assigned
        // or implicitly connected to the passed varray.
        Iterator<URI> arrayTargetNetworksIter = arrayTargetNetworks.iterator();
        while (arrayTargetNetworksIter.hasNext()) {
            URI networkURI = arrayTargetNetworksIter.next();
            Iterator<StoragePort> targetStoragePortsIter = arrayTargetMap.get(networkURI).iterator();
            while (targetStoragePortsIter.hasNext()) {
                StoragePort targetStoragePort = targetStoragePortsIter.next();
                StringSet taggedVArraysForPort = targetStoragePort.getTaggedVirtualArrays();
                if ((taggedVArraysForPort == null) || (!taggedVArraysForPort.contains(varrayURI.toString()))) {
                    targetStoragePortsIter.remove();
                }
            }
            
            // Eliminate any storage array connected networks who's storage ports aren't
            // explicitly assigned or implicitly connected to the passed varray.
            if (arrayTargetMap.get(networkURI).isEmpty()) {
                arrayTargetMap.remove(networkURI);
            }
        }
        
        List<URI> initiators = new ArrayList<URI>();
        Iterator<URI> rpSiteInitiatorsNetworksItr = rpSiteInitiatorNetworks.iterator();
        
        while (rpSiteInitiatorsNetworksItr.hasNext()) {
            URI initiatorURI = rpSiteInitiatorsNetworksItr.next();
            if (arrayTargetMap.keySet().contains(initiatorURI)) {      
                initiators.add(initiatorURI);
            }  
        }
        
        if (initiators.isEmpty()) {
            return false;
        }

        return true;   
    }
    
    /**
     * Determines if the given storage system has any active RecoverPoint protected
     * volumes under management.
     * 
     * @param id the storage system id
     * @return true if the storage system has active RP volumes under management. false otherwise.
     */
    public boolean containsActiveRpVolumes(URI id) {
        URIQueryResultList result = new URIQueryResultList();
        _dbClient.queryByConstraint(ContainmentConstraint.Factory.getStorageDeviceVolumeConstraint(id), result);
        Iterator<URI> volumeUriItr = result.iterator();

        while (volumeUriItr.hasNext()) {
            Volume volume = _dbClient.queryObject(Volume.class, volumeUriItr.next());
            // Is this an active RP volume? 
            if (volume != null && !volume.getInactive()
                    && volume.getRpCopyName() != null && !volume.getRpCopyName().isEmpty()) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Helper method that determines what the potential provisioned capacity is of a VMAX volume. 
     * The size returned may or may not be what the eventual provisioned capacity will turn out to be, but its pretty accurate estimate.
     *
     * @param requestedSize Size of the volume requested
     * @param volume volume
     * @param storageSystem storagesystem of the volume
     * @return potential provisioned capacity
     */
    public Long computeVmaxVolumeProvisionedCapacity(long requestedSize,
			Volume volume, StorageSystem storageSystem) {
		Long vmaxPotentialProvisionedCapacity = 0L;
		StoragePool expandVolumePool = _dbClient.queryObject(StoragePool.class, volume.getPool());
		long metaMemberSize = volume.getIsComposite() ? volume.getMetaMemberSize() : volume.getCapacity();
		long metaCapacity = volume.getIsComposite() ? volume.getTotalMetaMemberCapacity() : volume.getCapacity();
		MetaVolumeRecommendation metaRecommendation = MetaVolumeUtils.getExpandRecommendation(storageSystem, expandVolumePool, metaCapacity, requestedSize, metaMemberSize, volume.getThinlyProvisioned(), 
										_dbClient.queryObject(VirtualPool.class, volume.getVirtualPool()).getFastExpansion());
		
		if (metaRecommendation.isCreateMetaVolumes()) {
			long metaMemberCount = volume.getIsComposite() ? metaRecommendation.getMetaMemberCount()+volume.getMetaMemberCount() :
		        metaRecommendation.getMetaMemberCount()+1;
			vmaxPotentialProvisionedCapacity = metaMemberCount * metaRecommendation.getMetaMemberSize();             
		} else {
			vmaxPotentialProvisionedCapacity = requestedSize;
		}
		return vmaxPotentialProvisionedCapacity;
	}    
    
    /**
     * Get the FAPI RecoverPoint Client using the ProtectionSystem
     * 
     * @param ps ProtectionSystem object
     * @return RecoverPointClient object
     * @throws RecoverPointException
     */
    public static RecoverPointClient getRecoverPointClient(ProtectionSystem protectionSystem) throws RecoverPointException {
        RecoverPointClient recoverPointClient = null;       
        if (protectionSystem.getUsername() != null && !protectionSystem.getUsername().isEmpty()) {
            try {                
                List<URI> endpoints = new ArrayList<URI>();
                // Main endpoint that was registered by the user
                endpoints.add(new URI(HTTPS, null, protectionSystem.getIpAddress(), protectionSystem.getPortNumber(), RP_ENDPOINT, WSDL, null));
                // Add any other endpoints for cluster management ips we have
                for (String clusterManagementIp : protectionSystem.getClusterManagementIPs()) {
                    endpoints.add(new URI(HTTPS, null, clusterManagementIp, protectionSystem.getPortNumber(), RP_ENDPOINT, WSDL, null));
                }
                recoverPointClient = RecoverPointClientFactory.getClient(protectionSystem.getId(), endpoints, protectionSystem.getUsername(), protectionSystem.getPassword());
            } catch (URISyntaxException ex) {
                throw DeviceControllerExceptions.recoverpoint.errorCreatingServerURL(protectionSystem.getIpAddress(), protectionSystem.getPortNumber(), ex);
            }
        } else {
            throw DeviceControllerExceptions.recoverpoint.noUsernamePasswordSpecified(protectionSystem
                    .getIpAddress());
        }
                
        return recoverPointClient;
    }

    /**
     * Determines if the given volume descriptor applies to an RP source volume.
     * 
     * @param volumeDescriptor the volume descriptor.
     * @return true if the descriptor applies to an RP source volume, false otherwise.
     */
    public boolean isRPSource(VolumeDescriptor volumeDescriptor) {
    	boolean isSource = false;
    	if ((volumeDescriptor.getType().equals(VolumeDescriptor.Type.RP_SOURCE)) ||
			(volumeDescriptor.getType().equals(VolumeDescriptor.Type.RP_EXISTING_SOURCE)) ||
			(volumeDescriptor.getType().equals(VolumeDescriptor.Type.RP_EXISTING_PROTECTED_SOURCE)) || 
			(volumeDescriptor.getType().equals(VolumeDescriptor.Type.RP_VPLEX_VIRT_SOURCE))){
    		isSource = true; 
    	}
    	
    	return isSource;
    }
    
    /**
     * Determines if the given volume descriptor applies to an RP target volume.
     * 
     * @param volumeDescriptor the volume descriptor.
     * @return true if the descriptor applies to an RP target volume, false otherwise.
     */    
    public boolean isRPTarget(VolumeDescriptor volumeDescriptor) {
    	boolean isTarget = false;
    	if ((volumeDescriptor.getType().equals(VolumeDescriptor.Type.RP_TARGET)) ||
			(volumeDescriptor.getType().equals(VolumeDescriptor.Type.RP_VPLEX_VIRT_TARGET))){
    		isTarget = true; 
    	}    	
    	return isTarget;
    }

    /**
     * Determines if a volume is part of a MetroPoint configuration.
     * 
     * @param volume the volume.
     * @return true if this is a MetroPoint volume, false otherwise.
     */
    public boolean isMetroPointVolume(Volume volume) {		
		VirtualPool vpool = _dbClient.queryObject(VirtualPool.class, volume.getVirtualPool());
		 if (VirtualPool.vPoolSpecifiesMetroPoint(vpool)){			 
			_log.info("vpool specifies Metropoint RPCG requested");
			return true;
		 }
		return false;
	}
    
    /**
     * Checks to see if the volume is a production journal.  We check to see if the 
     * volume's rp copy name lines up with any of the given production copies.
     * 
     * @param productionCopies the production copies.
     * @param volume the volume.
     * @return true if the volume is a production journal, false otherwise.
     */
    public boolean isProductionJournal(Set<String> productionCopies, Volume volume) {  
      	for (String productionCopy : productionCopies) {
    		if (productionCopy.equalsIgnoreCase(volume.getRpCopyName())){
    			return true;
    		}
    	}    	
    	return false;
    }
    
    /**
     * Returns an existing journal volume to be used as journal for a new source volume. 
     * In 2.2, the largest sized journal volume already allocated to the CG will be returned. 
     *   
     * @param cgSourceVolumes
     * @param isMetropointStandby true only in the case when picking journals for MetroPoint stand-by copy
     * @return
     */
    public Volume selectExistingJournalForSourceVolume(List<Volume> cgSourceVolumes, boolean isMetropointStandby) {
    	Volume existingCGJournalVolume = null;
    	Map<Long, List<URI>> cgJournalsBySize = new TreeMap<Long, List<URI>>(Collections.reverseOrder());
    	Volume journal = null; 
    	for (Volume cgSourceVolume : cgSourceVolumes) {
    		
			if (isMetropointStandby) {
				if (cgSourceVolume.getSecondaryRpJournalVolume() != null) {
					journal = _dbClient.queryObject(Volume.class, cgSourceVolume.getSecondaryRpJournalVolume());
				}
			} else {
				if (cgSourceVolume.getRpJournalVolume() != null) {
	    			journal = _dbClient.queryObject(Volume.class, cgSourceVolume.getRpJournalVolume());
				}
			}
    		
			if (journal != null) {
				if (!cgJournalsBySize.containsKey(journal.getProvisionedCapacity())) {
					cgJournalsBySize.put(journal.getProvisionedCapacity(), new ArrayList<URI>());
				}
				cgJournalsBySize.get(journal.getProvisionedCapacity()).add(journal.getId());
			}
    	}
    	    	
    	// fetch the first journal in the list with the largest capacity.
    	for (Long journalSize : cgJournalsBySize.keySet()){    		
    		existingCGJournalVolume = _dbClient.queryObject(Volume.class, cgJournalsBySize.get(journalSize).get(0));
    		break;
    	}
    	//we should never hit this case, but just in case we do, just return the journal volume of the first source volume in the list. 
    	if (null == existingCGJournalVolume) {
    		URI existingJournalVolumeURI = isMetropointStandby ? cgSourceVolumes.get(0).getSecondaryRpJournalVolume() : cgSourceVolumes.get(0).getRpJournalVolume();
    		existingCGJournalVolume = _dbClient.queryObject(Volume.class, existingJournalVolumeURI);
    	}
    	return  existingCGJournalVolume;
    }
    
    /**
     * Returns an existing journal volume to be used as journal for a new target volume. 
     * In 2.2, the largest sized journal volume already allocated to the CG will be returned. 
     *   
     * @param cgVolumes Volumes in the consistency group
     * @param protectionVarrayTgtJournal Map of protection varray to RP target journal in that varray
     * @param varray protection varray
     * @param copyInternalSiteName RP internal site of the volume
     * @return existing Journal volume to be used/shared by volumes
     */
 
    public Volume selectExistingJournalForTargetVolume(List<Volume> cgVolumes, Map<URI, URI> protectionVarrayTgtJournal,  
    				URI varray, String copyInternalSiteName) {
    	Volume existingCGJournalVolume = null;
    	List<Volume> validExistingJournalVolumes = new ArrayList<Volume>();
    	Map<Long, List<URI>> cgJournalsBySize = new TreeMap<Long, List<URI>>(Collections.reverseOrder());
    	
    	//  If we are creating multiple resources, grab the journal we already created for this
    	//  protection virtual array and re-use it.    	
    	if (!protectionVarrayTgtJournal.isEmpty() && (null != protectionVarrayTgtJournal.get(varray))) {
    		return _dbClient.queryObject(Volume.class, protectionVarrayTgtJournal.get(varray));
    	}
    	
    	for (Volume cgVolume : cgVolumes) {
    		// Make sure we only consider exists CG target volumes from the same virtual array
    		if (cgVolume.getVirtualArray().equals(varray) && cgVolume.getInternalSiteName().equalsIgnoreCase(copyInternalSiteName)) {
    			if (null != cgVolume.getRpJournalVolume()) {
		    		Volume journal = _dbClient.queryObject(Volume.class, cgVolume.getRpJournalVolume());
		    		if (!cgJournalsBySize.containsKey(journal.getProvisionedCapacity())) {
		    			cgJournalsBySize.put(journal.getProvisionedCapacity(), new ArrayList<URI>());
		    		}
		    		cgJournalsBySize.get(journal.getProvisionedCapacity()).add(journal.getId());
		    		validExistingJournalVolumes.add(journal);
    			}
    		}
    	}
    	    	
    	// fetch the first journal in the list with the largest capacity.
    	for (Long journalSize : cgJournalsBySize.keySet()){    		
    		existingCGJournalVolume = _dbClient.queryObject(Volume.class, cgJournalsBySize.get(journalSize).get(0));
    		break;
    	}
    	//we should never hit this case, but just in case we do, just return the journal volume of the first source volume in the list. 
    	if (null == existingCGJournalVolume) {
    		existingCGJournalVolume = validExistingJournalVolumes.get(0);
    	}
    	return  existingCGJournalVolume;
    }
    
    
    /**
     * Return a list of journal volumes corresponding to the list of volumes to be deleted, that can be deleted.
     * The logic for this is simple - if a journal volume in protection set is part of only those volumes that 
     * are in the delete request, then that journal can be delete. If there are other protection set volumes
     * not part of the deleted that reference this journal then this journal will not be removed. 
     * 
     * @param protectionSet - protection set of the volumes that are deleted
     * @param rsetSrcVolumesToDelete - given the list of volumes to delete, determine journals corresponding to those that can be deleted.
     * @return List<URI> of primary or secondary (if valid) journals that can be deleted
     * @throws URISyntaxException
     */
    public Set<URI> determineJournalsToRemove(ProtectionSet protectionSet, List<URI> rsetSrcVolumesToDelete) {
		StringSet protectionSetVolumes = protectionSet.getVolumes(); 		
		Map<URI, HashSet<URI>> psJournalVolumeMap = new HashMap<URI, HashSet<URI>>();
		Map<URI, HashSet<URI>> volumeDeleteJournalVolumeMap = new HashMap<URI, HashSet<URI>>();
		Set<URI> journalsToDelete = new HashSet<URI>();
		
		
		// Build a map of journal volumes to protection set volumes that reference that journal volume.
		for (String psVolumeID : protectionSetVolumes) {
			Volume psVolume = _dbClient.queryObject(Volume.class, URI.create(psVolumeID));
			if(psVolume == null) continue;
			if (psVolume.getRpJournalVolume() != null) {
				if (psJournalVolumeMap.get(psVolume.getRpJournalVolume()) == null) {
					psJournalVolumeMap.put(psVolume.getRpJournalVolume(), new HashSet<URI>());
				}	        			
				psJournalVolumeMap.get(psVolume.getRpJournalVolume()).add(psVolume.getId());
			}
			
			if (psVolume.getSecondaryRpJournalVolume() != null) {
				if (psJournalVolumeMap.get(psVolume.getSecondaryRpJournalVolume()) == null) {
					psJournalVolumeMap.put(psVolume.getSecondaryRpJournalVolume(), new HashSet<URI>());
				}	        			
				psJournalVolumeMap.get(psVolume.getSecondaryRpJournalVolume()).add(psVolume.getId());
			}        			
		}
		
		// Given the source volumes, find the targets based on the rset name.
		// The source and target of a rset share the same rset name in a protection set. 
		Set<URI> rsetVolumesToDelete = new HashSet<URI>();
		for (String psVolumeID : protectionSetVolumes) {			
			Volume psVolume = _dbClient.queryObject(Volume.class, URI.create(psVolumeID));
			for(URI volume : rsetSrcVolumesToDelete) {
				Volume rsetVolume = _dbClient.queryObject(Volume.class, URI.create(volume.toString()));					
				if (rsetVolume != null &&
					rsetVolume.getRSetName() != null &&
					psVolume != null &&
					psVolume.getRSetName() != null && 	
					!psVolume.getPersonality().equalsIgnoreCase(Volume.PersonalityTypes.METADATA.toString()) &&
					rsetVolume.getRSetName().equalsIgnoreCase(psVolume.getRSetName())) {						
						rsetVolumesToDelete.add(URI.create(psVolumeID));					
				}						
			}			
		}
		
		// Build another map of all the journals that are referenced by the volumes in the delete request.
		// This map includes journals on the target side as well
		for (URI rsetVolumeToDelete : rsetVolumesToDelete) {
			Volume volume = _dbClient.queryObject(Volume.class, rsetVolumeToDelete);
			if (volume.getRpJournalVolume() != null) {
				if (volumeDeleteJournalVolumeMap.get(volume.getRpJournalVolume()) == null) {
					volumeDeleteJournalVolumeMap.put(volume.getRpJournalVolume(), new HashSet<URI>());
				}	        			
				volumeDeleteJournalVolumeMap.get(volume.getRpJournalVolume()).add(volume.getId());
			}
			
			if (volume.getSecondaryRpJournalVolume() != null) {
				if (volumeDeleteJournalVolumeMap.get(volume.getSecondaryRpJournalVolume()) == null) {
					volumeDeleteJournalVolumeMap.put(volume.getSecondaryRpJournalVolume(), new HashSet<URI>());
				}	        			
				volumeDeleteJournalVolumeMap.get(volume.getSecondaryRpJournalVolume()).add(volume.getId());
			}      
		}
		
		
		_log.info("ProtectionSet journalMap") ;
		for(URI psJournalEntry : psJournalVolumeMap.keySet()) {
			_log.info(String.format("%s : %s", psJournalEntry.toString(), Joiner.on(",").join(psJournalVolumeMap.get(psJournalEntry))));
		}
		
		_log.info("Volume delete journalMap") ;
		for(URI journalVolumeEntry : volumeDeleteJournalVolumeMap.keySet()) {
			_log.info(String.format("%s : %s", journalVolumeEntry.toString(), Joiner.on(",").join(volumeDeleteJournalVolumeMap.get(journalVolumeEntry))));
		}
		
		// Journals that are safe to remove are those journals in the volumes to delete list that are not
		// referenced by volumes in the protection set volumes.
		for (URI journalUri : volumeDeleteJournalVolumeMap.keySet()) {
			int journalReferenceCount = volumeDeleteJournalVolumeMap.get(journalUri).size();
			int psJournalReferenceCount = psJournalVolumeMap.get(journalUri).size();
			
			if (journalReferenceCount == psJournalReferenceCount) {
				_log.info("Deleting journal volume : " + journalUri.toString());
				journalsToDelete.add(journalUri);
			}					
		}		
		return journalsToDelete;
	}
    
    
	/**
	 * Gets a list of RecoverPoint consistency group volumes.
	 * @param blockConsistencyGroupUri
	 * @return
	 */
	public List<Volume> getCgVolumes(URI blockConsistencyGroupUri) {
        final List<Volume> cgVolumes = CustomQueryUtility
                .queryActiveResourcesByConstraint(_dbClient, Volume.class,
                    getVolumesByConsistencyGroup(blockConsistencyGroupUri)); 
        
        return cgVolumes;
	}
	
	/**
	 * Gets all the source volumes that belong in the specified RecoverPoint
	 * consistency group.
	 * @param blockConsistencyGroupUri
	 * @return
	 */
	public List<Volume> getCgSourceVolumes(URI blockConsistencyGroupUri) {
        List<Volume> cgSourceVolumes = new ArrayList<Volume>();
	    List<Volume> cgVolumes = getCgVolumes(blockConsistencyGroupUri);
        
        // Find the first existing source volume
        if (cgVolumes != null) {
            for (Volume cgVolume : cgVolumes) {
                if (cgVolume.getPersonality().equals(PersonalityTypes.SOURCE.toString())) {
                    cgSourceVolumes.add(cgVolume);
                }
            }
        }
        
        return cgSourceVolumes;
	}
	
	/**
	 * Gets all the volumes of the specified personality type in RecoverPoint
	 * consistency group.
	 * @param blockConsistencyGroupUri
	 * @return
	 */
	public List<Volume> getCgVolumes(URI blockConsistencyGroupUri, String personality) {
        List<Volume> cgPersonalityVolumes = new ArrayList<Volume>();
	    List<Volume> cgVolumes = getCgVolumes(blockConsistencyGroupUri);
        
        // Find the first existing source volume
        if (cgVolumes != null) {
            for (Volume cgVolume : cgVolumes) {
                if (cgVolume.getPersonality() != null && 
                		cgVolume.getPersonality().equals(personality)) {
                    cgPersonalityVolumes.add(cgVolume);
                }
            }
        }
        
        return cgPersonalityVolumes;
	}
	
	/** 
	 * 
	 * Helper method that computes if journal volumes are required to be provisioned and added to the RP CG.
	 *  
	 * @param journalPolicy
	 * @param cg
	 * @param size
	 * @param volumeCount
	 * @param personality
	 * @param copyInternalSiteName 
	 * @param metropointSecondary
	 * @return
	 */
	public boolean isAdditionalJournalRequiredForCG(String journalPolicy, BlockConsistencyGroup cg, String size, Integer volumeCount, String personality,
														String copyInternalSiteName, boolean metropointSecondary) {
		boolean additionalJournalRequired = false;
		
		if (journalPolicy != null && (journalPolicy.endsWith("x") || journalPolicy.endsWith("X"))) {	
			List<Volume> cgVolumes = getCgVolumes(cg.getId(), personality);
			Set<URI> journalVolumeURIs = new HashSet<URI>();
			Long cgJournalSize = 0L; 
			Long cgJournalSizeInBytes = 0L;				
			
			// Get all the volumes of the specified personality (source/target)
			// Since multiple RP source/target volume might reference the same journal
			// we need to get a unique list of the journal volumes and use that to calculate sizes.
			for (Volume cgVolume : cgVolumes) {				
				if (copyInternalSiteName.equalsIgnoreCase(cgVolume.getInternalSiteName())) {
					if (metropointSecondary) {
						if (!NullColumnValueGetter.isNullURI(cgVolume.getSecondaryRpJournalVolume())) {
							journalVolumeURIs.add(cgVolume.getSecondaryRpJournalVolume());
						}
					} else {
						if (!NullColumnValueGetter.isNullURI(cgVolume.getRpJournalVolume())) {
							journalVolumeURIs.add(cgVolume.getRpJournalVolume());
						}
					}			
				}
			}
						
			for (URI journalVolumeURI : journalVolumeURIs) {
				Volume journalVolume = _dbClient.queryObject(Volume.class, journalVolumeURI);								
				cgJournalSize += journalVolume.getProvisionedCapacity(); 			
			}
			
			cgJournalSizeInBytes = SizeUtil.translateSize(String.valueOf(cgJournalSize));
			_log.info(String.format("Existing total metadata size for the CG : %s GB ", SizeUtil.translateSize(cgJournalSizeInBytes, SizeUtil.SIZE_GB)));;
						
			Long cgVolumeSize = 0L;
			Long cgVolumeSizeInBytes = 0L;			
			for(Volume cgVolume : cgVolumes) {
				cgVolumeSize += cgVolume.getProvisionedCapacity();
			}
			cgVolumeSizeInBytes = SizeUtil.translateSize(String.valueOf(cgVolumeSize));
			_log.info(String.format("Existing Source volume size (cumulative) : %s GB", SizeUtil.translateSize(cgVolumeSizeInBytes, SizeUtil.SIZE_GB)));
			
			Long newCgVolumeSizeInBytes = cgVolumeSizeInBytes +  (Long.valueOf(SizeUtil.translateSize(size)) * volumeCount);	
			_log.info(String.format("New cumulative source size after the operation would be : %s GB", SizeUtil.translateSize(newCgVolumeSizeInBytes, SizeUtil.SIZE_GB)));
			Float multiplier = Float.valueOf(journalPolicy.substring(0, journalPolicy.length()-1)).floatValue(); 
			_log.info(String.format("Based on VirtualPool's journal policy, journal capacity required is : %s", (SizeUtil.translateSize(newCgVolumeSizeInBytes, SizeUtil.SIZE_GB) * multiplier)));
			_log.info(String.format("Current allocated journal capacity : %s GB", SizeUtil.translateSize(cgJournalSizeInBytes, SizeUtil.SIZE_GB)));
			if (cgJournalSizeInBytes < (newCgVolumeSizeInBytes * multiplier)) {				
				additionalJournalRequired = true;
			}
		}
		
		StringBuilder msg = new StringBuilder();
		msg.append(personality + "-Journal" + " : ");
		
		if (additionalJournalRequired) {
			msg.append("Additional journal required");
		} else {
			msg.append("Additional journal NOT required");
		}
				 
		_log.info(msg.toString());
		return additionalJournalRequired;
	}

	
   /* Since there are several ways to express journal size policy, this helper method will take
    * the source size and apply the policy string to come up with a resulting size.
    *
    * @param sourceSizeStr size of the source volume
    * @param journalSizePolicy the policy of the journal size.  ("10gb", "min", or "3.5x" formats)
    * @return journal volume size result
    */
   public static long getJournalSizeGivenPolicy(String sourceSizeStr, String journalSizePolicy, int resourceCount) {       
       // first, normalize the size. user can specify as GB,MB, TB, etc
       Long sourceSizeInBytes = 0L;   

       // Convert the source size into bytes, if specified in KB, MB, etc.
       if (sourceSizeStr.contains(SizeUtil.SIZE_TB) || sourceSizeStr.contains(SizeUtil.SIZE_GB) 
    		   || sourceSizeStr.contains(SizeUtil.SIZE_MB) || sourceSizeStr.contains(SizeUtil.SIZE_B)) {
           sourceSizeInBytes = SizeUtil.translateSize(sourceSizeStr);                   
           
       } else {
    	   sourceSizeInBytes = Long.valueOf(sourceSizeStr);
       }

       Long totalSourceSizeInBytes = sourceSizeInBytes * resourceCount;        
       _log.info(String.format("getJournalSizeGivenPolicy : totalSourceSizeInBytes %s GB ", SizeUtil.translateSize(totalSourceSizeInBytes, SizeUtil.SIZE_GB)));
       
       // First check: If the journalSizePolicy is not specified or is null, then perform the default math.
       // Default journal size is 10GB if source volume size times 0.25 is less than 10GB, else its 0.25x(source size)
       if (journalSizePolicy == null || journalSizePolicy.equals(NullColumnValueGetter.getNullStr())) {            
           if (DEFAULT_RP_JOURNAL_SIZE_IN_BYTES < ( totalSourceSizeInBytes * RP_DEFAULT_JOURNAL_POLICY) ) {          	  
               return (long)((totalSourceSizeInBytes * RP_DEFAULT_JOURNAL_POLICY));
           } else{                    	   
               return DEFAULT_RP_JOURNAL_SIZE_IN_BYTES;
           }
       }
       
       // Second Check: if the journal policy specifies min, then return default journal size
       if(journalSizePolicy.equalsIgnoreCase("min")) {    	   
       	return DEFAULT_RP_JOURNAL_SIZE_IN_BYTES;
       }
      
       // Third check: If the policy is a multiplier, perform the math, respecting the minimum value
       if (journalSizePolicy.endsWith("x") || journalSizePolicy.endsWith("X")) {
           float multiplier = Float.valueOf(journalSizePolicy.substring(0, journalSizePolicy.length()-1)).floatValue();            
           long journalSize =  ((long)(totalSourceSizeInBytes.longValue() * multiplier) < DEFAULT_RP_JOURNAL_SIZE_IN_BYTES) ? DEFAULT_RP_JOURNAL_SIZE_IN_BYTES : (long)(totalSourceSizeInBytes.longValue() * multiplier);          
           return journalSize;
       }

       // If the policy is an abbreviated value.
       // This is the only way to get a value less than minimally allowed.
       // Good in case the minimum changes or we're wrong about it per version.       
       return SizeUtil.translateSize(journalSizePolicy);
   }
 
   /**
    * Determines if a Volume is being referenced as an associated volume by an RP+VPlex
    * volume of a specified personality type (SOURCE, TARGET, METADATA, etc.).
    * 
    * @param volume the volume we are trying to find a parent RP+VPlex volume reference for.
    * @param dbClient the DB client.
    * @param types the personality types. 
    * @return true if this volume is associated to an RP+VPlex journal, false otherwise.
    */
   public static boolean isAssociatedToRpVplexType(Volume volume, DbClient dbClient, PersonalityTypes... types) {
       final List<Volume> vplexVirtualVolumes = CustomQueryUtility
               .queryActiveResourcesByConstraint(dbClient, Volume.class,
                getVolumesByAssociatedId(volume.getId().toString()));
   	
       for (Volume vplexVirtualVolume : vplexVirtualVolumes) {
    	   if (NullColumnValueGetter.isNotNullValue(vplexVirtualVolume.getPersonality())) {
    		   // If the personality type matches any of the passed in personality
    		   // types, we can return true.
    		   for (PersonalityTypes type : types) {
    			   if (vplexVirtualVolume.getPersonality().equals(type.name())) {
    				   return true;
    			   }
    		   }
    	   }
       }
       
       return false;
   }

    /**
     * returns the list of copies residing on the standby varray given the active production volume in a 
     * Metropoint environment
     * @param volume the active produciton volume
     * @return
     */
    public List<Volume> getMetropointStandbyCopies(Volume volume) {
        
        // TODO: this could be simplified:
        // Bharath: "if you want a simple way to do this, you could also get only the target volumes of a 
        // given source using volume.getRpTargets() and then check only those volumes against the varray"
        
        List<Volume> standbyCopies = new ArrayList<Volume>();
        
        // look for the standby varray in the volume's vpool
        VirtualPool vpool = _dbClient.queryObject(VirtualPool.class, volume.getVirtualPool());
        StringMap varrayVpoolMap = vpool.getHaVarrayVpoolMap();
        if (varrayVpoolMap != null && !varrayVpoolMap.isEmpty()) {
            URI standbyVarrayId = URI.create(varrayVpoolMap.keySet().iterator().next());
            
            // now loop through the replication set volumes and look for any copies from the standby varray
            for (Volume rsetVol : _dbClient.queryObject(Volume.class, getReplicationSetVolumes(volume))) {
                if (rsetVol.getVirtualArray().equals(standbyVarrayId)) {
                    standbyCopies.add(rsetVol);
                }
            }
        }
        return standbyCopies;
    }
    
    /**
     * Check to see if the target volume (based on varray) has already been provisioned
     * 
     * @param volume Source volume to check
     * @param varrayToCheckURI URI of the varray we're looking for Targets
     * @param dbClient DBClient
     * @return The target volume found or null otherwise
     */
    public static Volume findAlreadyProvisionedTargetVolume(Volume volume, URI varrayToCheckURI, DbClient dbClient) {
        Volume alreadyProvisionedTarget = null;
        if (volume.checkForRp() 
                && volume.getRpTargets() != null
                && NullColumnValueGetter.isNotNullValue(volume.getPersonality())
                && volume.getPersonality().equals(Volume.PersonalityTypes.SOURCE.name())) {
            // Loop through all the targets, check to see if any of the target volumes have
            // the same varray URI as the one passed in.
            for (String targetVolumeId : volume.getRpTargets()) {
                Volume targetVolume = dbClient.queryObject(Volume.class, URI.create(targetVolumeId));
                if (targetVolume.getVirtualArray().equals(varrayToCheckURI)) {
                    alreadyProvisionedTarget = targetVolume;
                    break;
                }
            }
        }
        
        return alreadyProvisionedTarget;         
    }
    
    /**
     * Helper method to retrieve all related volumes from a Source Volume
     * 
     * @param sourceVolumeURI The source volume URI
     * @param dbClient DBClient
     * @param includeBackendVolumes Flag to optionally have backend volumes included (VPLEX)
     * @param includeJournalVolumes Flag to optionally have journal volumes included
     * @return All volumes related to the source volume
     */
    public static Set<Volume> getAllRelatedVolumesForSource(URI sourceVolumeURI, DbClient dbClient, boolean includeBackendVolumes, boolean includeJournalVolumes) {
        Set<Volume> allRelatedVolumes = new HashSet<Volume>();
        
        if (sourceVolumeURI != null) {
            Volume sourceVolume = dbClient.queryObject(Volume.class, sourceVolumeURI);
            
            if (sourceVolume != null  
                    && NullColumnValueGetter.isNotNullValue(sourceVolume.getPersonality())
                    && sourceVolume.getPersonality().equals(Volume.PersonalityTypes.SOURCE.name())) {
                allRelatedVolumes.add(sourceVolume);
                
                if (includeJournalVolumes) {
                    Volume primaryJournalVolume = dbClient.queryObject(Volume.class, sourceVolume.getRpJournalVolume());
                    allRelatedVolumes.add(primaryJournalVolume);
                    
                    if (!NullColumnValueGetter.isNullURI(sourceVolume.getSecondaryRpJournalVolume())) {
                        Volume secondaryJournalVolume = dbClient.queryObject(Volume.class, sourceVolume.getSecondaryRpJournalVolume());
                        allRelatedVolumes.add(secondaryJournalVolume);
                    }
                }
    
                if (sourceVolume.getRpTargets() != null) {
                    for (String targetVolumeId : sourceVolume.getRpTargets()) {
                        Volume targetVolume = dbClient.queryObject(Volume.class, URI.create(targetVolumeId));
                        allRelatedVolumes.add(targetVolume);
                        
                        if (includeJournalVolumes) {
                            Volume targetJournalVolume = dbClient.queryObject(Volume.class, targetVolume.getRpJournalVolume());
                            allRelatedVolumes.add(targetJournalVolume);
                        }
                    }
                }
                
                List<Volume> allBackendVolumes = new ArrayList<Volume>();
                    
                if (includeBackendVolumes) {                    
                    for (Volume volume : allRelatedVolumes) {                        
                        if (volume.getAssociatedVolumes() != null 
                                && !volume.getAssociatedVolumes().isEmpty()) {
                            for (String associatedVolId : volume.getAssociatedVolumes()) {                 
                                Volume associatedVolume = dbClient.queryObject(Volume.class, URI.create(associatedVolId));
                                allBackendVolumes.add(associatedVolume);
                            }
                        }
                    }
                }
                
                allRelatedVolumes.addAll(allBackendVolumes);                
            }
        }
        
        return allRelatedVolumes;
    }
    
}
