/**
* Copyright 2015 EMC Corporation
* All Rights Reserved
 */
/**
 *  Copyright (c) 2008-2013 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */
package com.emc.storageos.api.service.impl.resource.utils;


import java.math.BigInteger;
import java.net.URI;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.api.service.authorization.PermissionsHelper;
import com.emc.storageos.api.service.impl.placement.VirtualPoolUtil;
import com.emc.storageos.api.service.impl.resource.ArgValidator;
import com.emc.storageos.api.service.impl.resource.utils.PropertySetterUtil.FileSystemObjectProperties;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.*;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedFileSystem;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedFileSystem.SupportedFileSystemCharacterstics;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedFileSystem.SupportedFileSystemInformation;
import com.emc.storageos.db.exceptions.DatabaseException;
import com.emc.storageos.svcs.errorhandling.resources.APIException;
import com.google.common.base.Joiner;

public class FileSystemIngestionUtil {
	private static Logger _logger = LoggerFactory.getLogger(FileSystemIngestionUtil.class);
    public static final String UNMANAGEDFILESYSTEM = "UNMANAGEDFILESYSTEM";
    public static final String FILESYSTEM = "FILESYSTEM";

   
    /**
     * Validation Steps
     * 1. validate PreExistingFileSystem uri.
     * 2. Check PreExistingFileSystem is under Bourne Management already.
     * 3. Check whether given CoS is present in the PreExistingFileSystems Supported CoS List
     * 
     * @param UnManagedFileSystems
     * @param cos
     * @throws Exception
     */
    public static void isIngestionRequestValidForUnManagedFileSystems(
            List<URI> UnManagedFileSystems, VirtualPool cos,DbClient dbClient)
            throws DatabaseException {
        for (URI unManagedFileSystemUri : UnManagedFileSystems) {
	    ArgValidator.checkUri(unManagedFileSystemUri);
            UnManagedFileSystem unManagedFileSystem = dbClient.queryObject(UnManagedFileSystem.class,
                    unManagedFileSystemUri);
            ArgValidator.checkEntityNotNull(unManagedFileSystem, unManagedFileSystemUri, false);
            
            if (null == unManagedFileSystem.getFileSystemCharacterstics() || null == unManagedFileSystem.getFileSystemInformation()) {
            	continue;
            }
            StringSetMap unManagedFileSystemInformation = unManagedFileSystem
                    .getFileSystemInformation();
            
            String fileSystemNativeGuid = unManagedFileSystem.getNativeGuid().replace(
                    UNMANAGEDFILESYSTEM, FILESYSTEM);
            
            if (VirtualPoolUtil.checkIfFileSystemExistsInDB(fileSystemNativeGuid, dbClient)) {
            	throw APIException.internalServerErrors.objectAlreadyManaged("FileSystem", fileSystemNativeGuid);
            }
            
            checkStoragePoolValidForUnManagedFileSystemUri(unManagedFileSystemInformation,
                    dbClient, unManagedFileSystemUri);
            
            checkVirtualPoolValidForGivenUnManagedFileSystemUris(unManagedFileSystemInformation, unManagedFileSystemUri,
                cos.getId());
            //TODO: Today, We bring in all the volumes that are exported.We need to add support to bring in all the related FS exports
            //checkUnManagedFileSystemAlreadyExported(unManagedFileSystem);
        }
    }
    
    /**
     * check if unManagedFileSystem is already exported to Host
     * @param unManagedFileSystem
     * @throws Exception
     */
    private static void checkUnManagedFileSystemAlreadyExported(
            UnManagedFileSystem unManagedFileSystem) throws Exception {
        StringMap unManagedFileSystemCharacteristics = unManagedFileSystem
        
                .getFileSystemCharacterstics();
        String isFileSystemExported = unManagedFileSystemCharacteristics
                .get(SupportedFileSystemCharacterstics.IS_FILESYSTEM_EXPORTED.toString());
        if (null != isFileSystemExported && Boolean.parseBoolean(isFileSystemExported)) {
        	throw APIException.internalServerErrors.objectAlreadyExported("FileSystem", unManagedFileSystem.getId());            
        }
    }
    /**
     * Check if valid storage Pool is associated with UnManaged FileSystem Uri is valid.
     * @param unManagedFileSystemInformation
     * @param dbClient
     * @param unManagedFileSystemUri
     * @throws Exception
     */
    private static void checkStoragePoolValidForUnManagedFileSystemUri(
            StringSetMap unManagedFileSystemInformation, DbClient dbClient,
            URI unManagedFileSystemUri) throws DatabaseException {
        String pool = PropertySetterUtil.extractValueFromStringSet(FileSystemObjectProperties.STORAGE_POOL.toString(),
                unManagedFileSystemInformation);
        if (null == pool) {
        	throw APIException.internalServerErrors.storagePoolError("", "FileSystem", unManagedFileSystemUri);          
        }
        StoragePool poolObj = dbClient.queryObject(StoragePool.class, URI.create(pool));
        if (null == poolObj) {
        	throw APIException.internalServerErrors.noStoragePool(pool, "FileSystem", unManagedFileSystemUri);           
        }
    }

    /**
     * Get Supported CoS from PreExistingFileSystem Storage Pools.
     * Verify if the given CoS is part of the supported CoS List.
     * 
     * @param preExistFileSystemInformation
     * @param cosUri
     */
    private static void checkVirtualPoolValidForGivenUnManagedFileSystemUris(
        StringSetMap preExistFileSystemInformation, URI unManagedFileSystemUri, URI cosUri) {
        //TODO: Currently the assumption is that CoS already exists prior to discovey of unmanaged fileystems.
        StringSet supportedCosUris = preExistFileSystemInformation
                .get(UnManagedFileSystem.SupportedFileSystemInformation.SUPPORTED_VPOOL_LIST
                        .toString());
        
        if (null == supportedCosUris) {
        	throw APIException.internalServerErrors.storagePoolNotMatchingVirtualPool("FileSystem", unManagedFileSystemUri);            
        }
       
        if (!supportedCosUris.contains(cosUri.toString())) {
        	throw APIException.internalServerErrors.virtualPoolNotMatchingStoragePool(cosUri, "FileSystem", unManagedFileSystemUri, Joiner.on("\t").join(supportedCosUris));           
        }
    }

    /**
     * Gets and verifies the CoS passed in the request.
     * 
     * @param project
     *            A reference to the project.
     * @param param
     *            The FileSystem create post data.
     * @return A reference to the CoS.
     */
    public static VirtualPool getVirtualPoolForFileSystemCreateRequest(
            Project project, URI cosUri, PermissionsHelper permissionsHelper,
            DbClient dbClient) {
        ArgValidator.checkUri(cosUri);
        VirtualPool cos = dbClient.queryObject(VirtualPool.class, cosUri);
        ArgValidator.checkEntity(cos, cosUri, false);
        
        if (!VirtualPool.Type.file.name().equals(cos.getType()))
                throw APIException.badRequests.virtualPoolNotForFileBlockStorage(VirtualPool.Type.file.name());
     
        permissionsHelper.checkTenantHasAccessToVirtualPool(project.getTenantOrg().getURI(), cos);
             
        return cos;
    }

    /**
     * Gets and verifies that the varray passed in the request is
     * accessible to the tenant.
     * 
     * @param project
     *            A reference to the project.
     * @param neighborhoodUri 
     *            The Varray URI.
     * 
     * @return A reference to the varray.
     */
    public static VirtualArray getVirtualArrayForFileSystemCreateRequest(
            Project project, URI neighborhoodUri, PermissionsHelper permissionsHelper,
            DbClient dbClient) {
        ArgValidator.checkUri(neighborhoodUri);
        VirtualArray neighborhood = dbClient.queryObject(VirtualArray.class,
                neighborhoodUri);
        ArgValidator.checkEntity(neighborhood, neighborhoodUri, false);
        permissionsHelper.checkTenantHasAccessToVirtualArray(project.getTenantOrg().getURI(), neighborhood);
        
    
        return neighborhood;
    }
    
    public static long getTotalUnManagedFileSystemCapacity(DbClient dbClient,
			List<URI> unManagedFileSystemUris) {
		BigInteger totalUnManagedFileSystemCapacity = new BigInteger("0");
		try {
			Iterator<UnManagedFileSystem> unManagedFileSystems = dbClient.queryIterativeObjects(UnManagedFileSystem.class,
							unManagedFileSystemUris);

			while (unManagedFileSystems.hasNext()) {
				UnManagedFileSystem unManagedFileSystem = unManagedFileSystems.next();
				StringSetMap unManagedFileSystemInfo = unManagedFileSystem
						.getFileSystemInformation();
				if (null == unManagedFileSystemInfo) {
					continue;
				}
				String unManagedFileSystemCapacity = PropertySetterUtil
						.extractValueFromStringSet(SupportedFileSystemInformation.ALLOCATED_CAPACITY
										.toString(), unManagedFileSystemInfo);
				if (null != unManagedFileSystemCapacity && !unManagedFileSystemCapacity.isEmpty()) {
					totalUnManagedFileSystemCapacity = totalUnManagedFileSystemCapacity
							.add(new BigInteger(unManagedFileSystemCapacity));
				}

			}
		} catch (Exception e) {
			throw APIException.internalServerErrors.capacityComputationFailed();
		}
		return totalUnManagedFileSystemCapacity.longValue();
	}
}
