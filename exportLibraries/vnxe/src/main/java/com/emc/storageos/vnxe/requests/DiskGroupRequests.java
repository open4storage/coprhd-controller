/**
* Copyright 2015 EMC Corporation
* All Rights Reserved
 */
/**
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */
package com.emc.storageos.vnxe.requests;

import com.emc.storageos.vnxe.models.DiskGroup;

public class DiskGroupRequests extends KHRequests<DiskGroup> {
	
	private static final String URL = "/api/instances/diskGroup/";

	public DiskGroupRequests(KHClient client, String id) {
		super(client);
		_url = URL + id;
	}
	
	public DiskGroup get() {
		return getDataForOneObject(DiskGroup.class);
	}

}
