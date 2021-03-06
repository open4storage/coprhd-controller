/**
* Copyright 2015 EMC Corporation
* All Rights Reserved
 */
/**
 *  Copyright (c) 2012 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */

package com.emc.storageos.db.client.constraint;

import java.net.URI;

import com.emc.storageos.db.client.constraint.impl.LabelConstraintImpl;
import com.emc.storageos.db.client.constraint.impl.PrefixConstraintImpl;
import com.emc.storageos.db.client.impl.DataObjectType;
import com.emc.storageos.db.client.impl.TypeMap;
import com.emc.storageos.db.client.impl.ColumnField;
import com.emc.storageos.db.client.model.DataObject;

/**
 * Query constraint for type & prefix matching.  For example, give me FileShare
 * with label "foobar*"
 */
public interface PrefixConstraint extends Constraint {
    /**
     * Factory for creating prefix constraint
     */
    public static class Factory {
        // tags - prefix search
        public static PrefixConstraint getTagsPrefixConstraint(Class<? extends DataObject> clazz, String prefix, URI tenant) {
            DataObjectType doType = TypeMap.getDoType(clazz);
            return new PrefixConstraintImpl(tenant, prefix, doType.getColumnField("tags"));
        }

        // label - prefix search
        public static PrefixConstraint getLabelPrefixConstraint(Class<? extends DataObject> clazz, String prefix) {
            DataObjectType doType = TypeMap.getDoType(clazz);
            return new PrefixConstraintImpl(prefix, doType.getColumnField("label"));
        }

        // prefix indexed field - prefix search
        public static Constraint getConstraint(Class<? extends DataObject> type,
                                               String columeField,
                                               String prefix) {
            DataObjectType doType = TypeMap.getDoType(type);
            ColumnField field = doType.getColumnField(columeField);
            return new PrefixConstraintImpl(prefix, field);
        }

        // prefix indexed field - prefix search, scoped to resource uri
        public static Constraint getConstraint(Class<? extends DataObject> type,
                                               String columeField,
                                               String prefix,
                                               URI resourceUri) {
            DataObjectType doType = TypeMap.getDoType(type);
            ColumnField field = doType.getColumnField(columeField);
            return new PrefixConstraintImpl(resourceUri, prefix, field);
        }

        // prefix indexed field - full string match
        public static PrefixConstraint getFullMatchConstraint(Class<? extends DataObject> type,
                                               String columeField,
                                               String prefix) {
            DataObjectType doType = TypeMap.getDoType(type);
            ColumnField field = doType.getColumnField(columeField);
            return new LabelConstraintImpl(prefix, field);
        }
    }
}
