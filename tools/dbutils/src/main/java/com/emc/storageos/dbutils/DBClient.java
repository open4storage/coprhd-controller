/**
* Copyright 2015 EMC Corporation
* All Rights Reserved
 */
/**
 *  Copyright (c) 2008-2011 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */
package com.emc.storageos.dbutils;

import org.apache.commons.lang3.StringUtils;
import com.emc.storageos.db.client.TimeSeriesMetadata;
import com.emc.storageos.db.client.TimeSeriesQueryResult;
import com.emc.storageos.db.client.impl.DataObjectType;
import com.emc.storageos.db.client.impl.DbClientContext;
import com.emc.storageos.db.client.impl.EncryptionProviderImpl;
import com.emc.storageos.db.client.impl.TypeMap;
import com.emc.storageos.db.client.model.*;
import com.emc.storageos.db.common.DataObjectScanner;
import com.emc.storageos.db.common.DbSchemaChecker;
import com.emc.storageos.db.common.DependencyChecker;
import com.emc.storageos.db.common.DependencyTracker;
import com.emc.storageos.db.common.schema.DbSchemas;
import com.emc.storageos.db.exceptions.DatabaseException;
import com.emc.storageos.geo.service.impl.util.VdcConfigHelper;
import com.emc.storageos.geo.vdccontroller.impl.InternalDbClient;
import com.emc.storageos.geomodel.VdcConfig;
import com.emc.storageos.security.SerializerUtils;
import com.sun.jersey.core.spi.scanning.PackageNamesScanner;
import com.sun.jersey.spi.scanning.AnnotationScannerListener;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.crypto.SecretKey;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.*;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;


/**
 * DBClient - uses coordinator to find the service
 */
public class DBClient {
    private static final Logger log = LoggerFactory.getLogger(DBClient.class);

    private static final String pkgs = "com.emc.storageos.db.client.model";

    private static final String QUITCHAR = "q";
    private int listLimit = 100;
    private boolean turnOnLimit = false;
    private boolean activeOnly = false;

    private static final String PRINT_COUNT_RESULT = "Column Family %s's row count is: %s";
    private static final String REGEN_RECOVER_FILE_MSG = "Please regenerate the recovery " +
            "file from the node where the last add VDC operation was initiated.";
    private static final String KEY_DB = "dbKey";
    private static final String KEY_GEODB = "geodbKey";

    InternalDbClientImpl _dbClient = null;
    private DependencyChecker _dependencyChecker = null;

    HashMap<String, Class> _cfMap = new HashMap<String, Class>();
    ClassPathXmlApplicationContext ctx = null;

    DbClientContext _geodbContext = null;
    private VdcConfigHelper vdcConfHelper;
    private EncryptionProviderImpl geoEncryptionProvider;
    private EncryptionProviderImpl encryptionProvider;

    @SuppressWarnings("unchecked")
    public DBClient() {
    }

    public void init(){
        try {
            System.out.println("Initializing db client ...");
            ctx = new ClassPathXmlApplicationContext("/dbutils-conf.xml");
            InternalDbClientImpl dbClient = (InternalDbClientImpl) ctx.getBean("dbclient");
            _geodbContext = (DbClientContext) ctx.getBean("geodbclientcontext");
            vdcConfHelper = (VdcConfigHelper)ctx.getBean("vdcConfHelper");
            geoEncryptionProvider = (EncryptionProviderImpl)ctx.getBean("geoEncryptionProvider");
            encryptionProvider = (EncryptionProviderImpl)ctx.getBean("encryptionProvider");

            dbClient.start();
            _dbClient = dbClient;

            // scan for classes with @Cf annotation
            System.out.println("Initializing column family map ...");
            AnnotationScannerListener scannerListener = new AnnotationScannerListener(Cf.class);
            String[] packages = { pkgs };
            PackageNamesScanner scanner = new PackageNamesScanner(packages);
            scanner.scan(scannerListener);
            Iterator<Class<?>> it = scannerListener.getAnnotatedClasses().iterator();
            while (it.hasNext()) {
                Class clazz = it.next();
                //For TimeSeries, "getSerializer" doesn't have Name Annotation
                //_cfMap, doesnt need to get populated with TimeSeries
                //The fields of SchemaRecord don't have Name annotation either.
                if (DataObject.class.isAssignableFrom(clazz)) {
                    DataObjectType doType = TypeMap.getDoType(clazz);
                    _cfMap.put(doType.getCF().getName(), clazz);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public InternalDbClientImpl getDbClient() {
	    return _dbClient;
    }

    protected DbClientContext getGeoDbContext() {
        return _geodbContext;
    }

    public void stop() {
        if(_dbClient != null){
            _dbClient.stop();
        }
    }
   
    /**
     * Query for records with the given ids and type, and print the contents in human readable format
     * @param ids
     * @param clazz
     * @param <T>
     */
    private <T extends DataObject> int queryAndPrintRecords(List<URI> ids, Class<T> clazz)
            throws Exception {

        Iterator<T> objects;
        BeanInfo bInfo;
        int countLimit = 0;
        int countAll = 0;
        String input;
        BufferedReader buf = new BufferedReader(new InputStreamReader(System.in));

        try {
            objects = _dbClient.queryIterativeObjects(clazz, ids);
            bInfo = Introspector.getBeanInfo(clazz);
            while (objects.hasNext()) {
                T object = (T) objects.next();
                printBeanProperties(bInfo.getPropertyDescriptors(), object);
                countLimit++;
                countAll++;
                if (!turnOnLimit || countLimit != listLimit)
                    continue;
                System.out.println(String.format("Read %s rows ", countAll));
                do {
                    System.out.println("\nPress 'ENTER' to continue or 'q<ENTER>' to quit...");
                    input = buf.readLine();
                    if (input.isEmpty()) {
                        countLimit = 0;
                        break;
                    }
                    if (input.equalsIgnoreCase(QUITCHAR))
                        return countAll;
                } while (!input.isEmpty());
            }
        } catch (DatabaseException ex) {
            log.error("Error querying from db: " + ex);
            System.err.println("Error querying from db: " + ex);
            throw ex;
        } catch (IntrospectionException ex) {
            log.error("Unexpected exception getting bean info", ex);
            throw new RuntimeException("Unexpected exception getting bean info", ex);
        } finally {
            buf.close();
        }
        return countAll;
    }
    
    /**
     * Query for a record with the given id and type, and print the contents in human readable format
     * if query URI list, use queryAndPrintRecords(ids, clazz) method instead.
     * @param id
     * @param clazz
     * @param <T>
     */
    private <T extends DataObject> void queryAndPrintRecord(URI id, Class<T> clazz) throws Exception {
        T object = queryObject(id, clazz);

        if (object == null) {
            // its deleted
            System.out.println("id: " + id + " [ Deleted ]");
            return;
        }

        BeanInfo bInfo;

        try{
            bInfo = Introspector.getBeanInfo(clazz);
        } catch (IntrospectionException ex) {
            throw new RuntimeException("Unexpected exception getting bean info", ex);
        }

        printBeanProperties(bInfo.getPropertyDescriptors(), object);
    }
    
    /**
     * Print the contents in human readable format
     * 
     * @param pds
     * @param object
     * @throws Exception
     */
    private <T extends DataObject> void printBeanProperties(PropertyDescriptor[] pds,
            T object) throws Exception {
        System.out.println("id: " + object.getId().toString());
        Object objValue;
        Class type;
        for (PropertyDescriptor pd : pds) {
            // skip class property
            if (pd.getName().equals("class") || pd.getName().equals("id")) {
                continue;
            }

            objValue = pd.getReadMethod().invoke(object);
            if (objValue == null) {
                continue;
            }
            
            if(isEmptyStr(objValue)){
            	continue;
            }
            System.out.print("\t" + pd.getName() + " = ");

            Encrypt encryptAnnotation = pd.getReadMethod().getAnnotation(Encrypt.class);
            if (encryptAnnotation != null) {
                System.out.println("*** ENCRYPTED CONTENT ***");
                continue;
            } 

            type = pd.getPropertyType();
            if (type == URI.class) {
                System.out.println("URI: " + objValue);
            } else if (type == StringMap.class) {
                System.out.println("StringMap " + objValue);
            } else if (type == StringSet.class) {
                System.out.println("StringSet " + objValue);
            } else if (type == OpStatusMap.class) {
                System.out.println("OpStatusMap " + objValue);
            } else {
                System.out.println(objValue);
            }
        }
    }

    private boolean isEmptyStr(Object objValue) {
    	if(!(objValue instanceof String)){
    		return false;
    	}
    	return StringUtils.isEmpty((String)objValue);
	}

	/**
     * Query for a particular id in a ColumnFamily
     * @param id
     * @param cfName
     * @throws Exception
     */
    @SuppressWarnings("unchecked")
    public void query(String id, String cfName) throws Exception {
        Class clazz = _cfMap.get(cfName); // fill in type from cfName
        if (clazz == null) {
            System.err.println("Unknown Column Family: " + cfName);
            return;
        }
        if (!DataObject.class.isAssignableFrom(clazz)) {
            System.err.println("TimeSeries data not supported with this command.");
            return;
        }
        queryAndPrintRecord(URI.create(id), clazz);
    }

    /**
     * Iteratively list records from DB in a user readable format
     * @param cfName
     * @throws Exception
     */
    @SuppressWarnings("unchecked")
    public void listRecords(String cfName) throws Exception  {
        final Class clazz = _cfMap.get(cfName); // fill in type from cfName
        if (clazz == null) {
            System.err.println("Unknown Column Family: " + cfName);
            return;
        }
        if (!DataObject.class.isAssignableFrom(clazz)) {
            System.err.println("TimeSeries data not supported with this command.");
            return;
        }
        List<URI> uris = null;
        uris = getColumnUris(clazz, activeOnly);
        if (uris == null || !uris.iterator().hasNext()) {
            System.out.println("No records found");
            return;
        }
        int count = queryAndPrintRecords(uris, clazz);
        System.out.println("Number of All Records is: " + count);
    }

    /**
     * Stat query result  - writes out the result as XML
     */
    private static class StatQueryResult implements TimeSeriesQueryResult<Stat> {
        private StringBuilder builder = new StringBuilder("<stats>");
        private String filename = null;
        private int recCount = 0;

        StatQueryResult(String filename) {
            this.filename = filename;
        }

        @Override
        public void data(Stat data, long insertionTimeMs) {
	    BuildXML<Stat> xmlBuilder = new BuildXML<Stat>();
            String xml = xmlBuilder.writeAsXML(data, "stat");
            builder.append(xml);
            ++recCount;
        }

        @Override
        public void done() {
            builder.append("</stats>");
            XMLWriter writer = new XMLWriter();
            writer.writeXMLToFile(builder.toString(), filename);
            BuildXML.count = 0;
            System.out.println(" -> Querying Metrics Completed and Count of those Stats found are : " + recCount);
        }

        @Override
        public void error(Throwable e) {
            System.err.println("Error callback" + e);
            e.printStackTrace();
        }
    }

    private static class EventQueryResult implements TimeSeriesQueryResult<Event> {
        private StringBuilder builder = new StringBuilder("<events>");
        private String filename = null;
        private int recCount = 0;

        EventQueryResult(String filename) {
            this.filename = filename;
        }

        @Override
        public void data(Event data, long insertionTimeMs) {
	    BuildXML<Event> xmlBuilder = new BuildXML<Event>();
            String xml = xmlBuilder.writeAsXML(data, "event");
            builder.append(xml);
            ++recCount;
        }

        @Override
        public void done() {
            builder.append("</events>");
            XMLWriter writer = new XMLWriter();
            writer.writeXMLToFile(builder.toString(), filename);
            BuildXML.count = 0;
            System.out.println(" -> Querying For Events Completed and Count of those Events found are : " + recCount);
        }

        @Override
        public void error(Throwable e) {
            System.err.println("Error callback" + e);
        }
    }

    private static class AuditQueryResult implements TimeSeriesQueryResult<AuditLog> {
        private StringBuilder builder = new StringBuilder("<audits>");
        private String filename = null;
        private int recCount = 0;

        AuditQueryResult(String filename) {
            this.filename = filename;
        }

        @Override
        public void data(AuditLog data, long insertionTimeMs) {
	    BuildXML<AuditLog> xmlBuilder = new BuildXML<AuditLog>();
            String xml = xmlBuilder.writeAsXML(data, "audit");
            builder.append(xml);
            ++recCount;
        }

        @Override
        public void done() {
            builder.append("</audits>");
            XMLWriter writer = new XMLWriter();
            writer.writeXMLToFile(builder.toString(), filename);
            BuildXML.count = 0;
            System.out.println(" -> Querying For audits completed and count of the audits found are : " + recCount);
        }

        @Override
        public void error(Throwable e) {
            System.err.println("Error callback" + e);
        }
    }

    /**
     * Query stats
     * @param dateTime
     */
    public void queryForCustomDayStats(DateTime dateTime, String filename) {
        System.out.println("\n\n -> Querying Stats");
        ExecutorService executor = Executors.newFixedThreadPool(100);
        StatQueryResult result = new StatQueryResult(filename);
        try {
            _dbClient.queryTimeSeries(StatTimeSeries.class, dateTime, TimeSeriesMetadata.TimeBucket.HOUR, result, executor);
            System.out.println(" --- Job Exceution for Querying Stats completed ---\n\n");
            return;
        } catch (DatabaseException e) {
            System.err.println("Exception Query" + e);
            e.printStackTrace();
        }
    }

    /**
     * Query events
     * @param dateTime
     */
    public void queryForCustomDayEvents(DateTime dateTime, String filename) {
        System.out.println("\n\n -> Querying Events");
        ExecutorService executor = Executors.newFixedThreadPool(100);
        EventQueryResult result = new EventQueryResult(filename);
        try {
            _dbClient.queryTimeSeries(EventTimeSeries.class, dateTime, TimeSeriesMetadata.TimeBucket.HOUR, result, executor);
            System.out.println(" --- Job Exceution for Querying Events completed ---");
            return;
        } catch (DatabaseException e) {
            System.err.println("Exception Query" + e);
            e.printStackTrace();
        }
    }

    /**
     * Query audit
     * @param dateTime
     */
    public void queryForCustomDayAudits(DateTime dateTime, String filename) {
        System.out.println("\n\n -> Querying Audits");
        ExecutorService executor = Executors.newFixedThreadPool(100);
        AuditQueryResult result = new AuditQueryResult(filename);
        try {
            _dbClient.queryTimeSeries(AuditLogTimeSeries.class, dateTime, TimeSeriesMetadata.TimeBucket.HOUR, result, executor);
            System.out.println(" --- Job Exceution for Querying Audits completed ---\n\n");
            return;
        } catch (DatabaseException e) {
            System.err.println("Exception Query" + e);
            e.printStackTrace();
        }
    }

    /**
     * Delete object 
     * @param id
     * @param cfName
     * @param force
     */
    public void delete(String id, String cfName, boolean force) throws Exception {
        Class clazz = _cfMap.get(cfName); // fill in type from cfName
        if (clazz == null) {
            System.err.println("Unknown Column Family: " + cfName);
            return;
        }

        boolean deleted = queryAndDeleteObject(URI.create(id), clazz, force);

        if (deleted)
            log.info("The object {} is deleted from the column family {}", id, cfName);
        else
            log.info("The object {} is NOT deleted from the column family {}", id, cfName);
    }

    /**
     * Query for a record with the given id and type, and print the contents in human readable format
     * @param id
     * @param clazz
     * @param <T>
     */
    private <T extends DataObject> boolean queryAndDeleteObject(URI id, Class<T> clazz, boolean force)
                throws Exception {
        if (_dependencyChecker == null) {
            DataObjectScanner dataObjectscanner = (DataObjectScanner) ctx.getBean("dataObjectScanner");
            DependencyTracker dependencyTracker = dataObjectscanner.getDependencyTracker();
            _dependencyChecker = new DependencyChecker(_dbClient, dependencyTracker);
        }

        if (_dependencyChecker.checkDependencies(id, clazz, false) != null) {
            if (!force) {
                System.err.println(String.format("Failed to delete the object %s: there are active dependencies", id));
                return false;
            }
            log.info("Force to delete object {} that has active dependencies", id);
        }
           
        T object = queryObject(id, clazz);

        if (object == null) {
            System.err.println(String.format("The object %s has already been deleted",id));
            return false;
        }

        if ((object.canBeDeleted() == null) || force) {
            if (object.canBeDeleted() != null)
                log.info("Force to delete object {} that can't be deleted", id);

            _dbClient.removeObject(object);
            return true;
        }

        System.err.println(String.format("The object %s can't be deleted",id));

        return false;
    }
    
    private <T extends DataObject> T queryObject(URI id, Class<T> clazz) throws Exception {
        T object = null;
        try {
            object = _dbClient.queryObject(clazz, id);
        } catch (DatabaseException ex) {
            System.err.println("Error querying from db: " + ex);
            throw ex;
        }

        return object;
    }

    /**
     * Get the column family row count 
     * @param cfName
     * @param isActive
     */
    @SuppressWarnings("unchecked")
    public int getRowCount(String cfName, boolean isActive) throws Exception {
        Class clazz = _cfMap.get(cfName); // fill in type from cfName
        int rowCount = 0;
        if (clazz == null) {
            System.out.println("Unknown Column Family: " + cfName);
            return -1;
        }
        List<URI> uris = null;
        uris = getColumnUris(clazz, isActive);
        if ( uris == null || !uris.iterator().hasNext() ) {
            System.out.println(String.format(PRINT_COUNT_RESULT, cfName, rowCount));
            return -1;
        }
        for (URI uri: uris) {
            rowCount ++;
        }
        System.out.println(String.format(PRINT_COUNT_RESULT, cfName, rowCount));
        return rowCount;
    }

    /**
     * Record number of column family: Stats, Evetns, AuditLogs
     */
    public int countTimeSeries(String cfName, Calendar startTime, Calendar endTime){
        return _dbClient.countTimeSeries(cfName, startTime, endTime);
            }

    /**
     * get the keys of column family for list/count
     */
    private List<URI> getColumnUris(Class clazz, boolean isActive){
        List<URI> uris = null;
        try {
            uris = _dbClient.queryByType(clazz, isActive);
        } catch (DatabaseException e) {
            System.err.println("Error querying from db: " + e);
            return null;
        }
        return uris;
    }

    public void setListLimit(int listLimit) {
        this.listLimit = listLimit;
    }
    
    public void setTurnOnLimit(boolean turnOnLimit) {
        this.turnOnLimit = turnOnLimit;
    }

    public void setActiveOnly(boolean activeOnly) {
        this.activeOnly = activeOnly;
    }

    /**
     * Read the schema record from db and dump it into a specified file
     *
     * @param schemaVersion
     * @param dumpFilename
     */
    public void dumpSchema(String schemaVersion, String dumpFilename) {
        SchemaRecord schemaRecord = _dbClient.querySchemaRecord(schemaVersion);
        if (schemaRecord == null) {
            System.err.println("No such schema version: " + schemaVersion);
            return;
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(dumpFilename));
                BufferedReader reader = new BufferedReader(new StringReader(
                schemaRecord.getSchema()))) {
            DbSchemas dbSchemas = DbSchemaChecker.unmarshalSchemas(schemaVersion, reader);
            writer.write(DbSchemaChecker.marshalSchemas(dbSchemas, schemaVersion));
            System.out.println("Db Schema version " + schemaVersion + " successfully" +
                    " dumped to file " + dumpFilename);
        } catch (IOException e) {
            System.err.println("Caught IOException");
            e.printStackTrace(System.err);
        }
    }
    
    /**
     * Recover the system after add/remove vdc failures from recover file
     * @param recoverFileName
     */
    public void recoverVdcConfigFromRecoverFile(String recoverFileName) {
        List<VdcConfig> newVdcConfigList = loadRecoverFileToRecoverInfo(recoverFileName);
        InternalDbClient geoDbClient = (InternalDbClient)ctx.getBean("geodbclient");
        geoDbClient.stopClusterGossiping();
        vdcConfHelper.syncVdcConfig(newVdcConfigList, null, true);
        try {
            Thread.sleep(30000);
        } catch (InterruptedException e) {
            log.error("Error in recover Vdc Config, e="+e);
        }
        System.out.println("Recover successfully, please wait for the whole vdc reboot.");
    }
    
    /**
     * Load the specific recover file to generate newVdcConfigList for recovery
     * @param recoverFileName
     * @return
     */
    private List<VdcConfig> loadRecoverFileToRecoverInfo(String recoverFileName){
        List<VdcConfig> newVdcConfigList = new ArrayList<VdcConfig>();
        Document doc = null;
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder();
            File f = new File(recoverFileName);
            BufferedInputStream in = new BufferedInputStream(new FileInputStream(f));
            DataInputStream dis = new DataInputStream(in);
            byte[] loadBytes = new byte[(int) f.length()];
            dis.readFully(loadBytes);
            String decryptString = geoEncryptionProvider.decrypt(loadBytes);
            InputStream decryptStringStream = new ByteArrayInputStream(
                    decryptString.getBytes());
            doc = builder.parse(decryptStringStream);
        } catch (Exception e) {
            System.err.println("Fail to read recover file, if you are not in VDC1 now, "
                    + "please copy the recover file from VDC1 to this VDC and then issue recover command. e= " + e);
            throw new RuntimeException("Recover file not found: " + recoverFileName);
        }
        
        Element root = doc.getDocumentElement();
        NodeList vdcConfigNodes = root.getElementsByTagName("VdcConfig");
        
        for (int i = 0; i < vdcConfigNodes.getLength(); i++) {
            Element vdcConfigNode = (Element) vdcConfigNodes.item(i);
            VdcConfig newVdcConfig = new VdcConfig();
            newVdcConfig.setId(URI.create(vdcConfigNode.getAttribute("id")));
            
            NodeList fields = vdcConfigNode.getElementsByTagName("field");
            for (int j = 0; j < fields.getLength(); j++) {
                Element field = (Element) fields.item(j);
                Method method = null;
                try {
                    if (field.getAttribute("value") == null
                            || field.getAttribute("value").equals("")) {
                        continue;
                    }
                    Class type = Class.forName(field.getAttribute("type"));
                    method = newVdcConfig.getClass().getMethod(
                            "set"+field.getAttribute("name"),
                            type);
                    if (type == Integer.class) {
                        method.invoke(newVdcConfig, 
                                Integer.valueOf(field.getAttribute("value")));
                    }
                    else if (type == Long.class) {
                        method.invoke(newVdcConfig,
                                Long.valueOf(field.getAttribute("value")));
                    }
                    else if (type == HashMap.class) {
                        String loadString = field.getAttribute("value").replaceAll("[{}]", "");
                        if (loadString.equals("")) continue;
                        HashMap<String, String> map = new HashMap<String, String>();
                        String[] kvs = loadString.split(",");
                        for (String kv : kvs) {
                            String[] onekv = kv.split("="); 
                            String key = onekv[0].trim();
                            String value = onekv[1].trim();
                            map.put(key, value);
                        }
                        method.invoke(newVdcConfig, map);
                    }
                    else {
                        method.invoke(newVdcConfig, field.getAttribute("value"));
                    }
                } catch (Exception e) {
                    System.err.println("Reflect fail,method= " + method + "e= " + e);
                }
                
            }
            newVdcConfigList.add(newVdcConfig);
        }
        return newVdcConfigList;
    }

    /**
     * Dump the vdc config backup info for recovery
     * @param RecoverFileName
     */
    public void dumpRecoverInfoToRecoverFile(String RecoverFileName) {
        List<VdcConfig> newVdcConfigList = readRecoverBackupInfo();
        verifyVdcConfigs(newVdcConfigList);
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = null;
        try {
            builder = dbf.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            System.err.println("xml builder error: " + e);
        }
        Document doc = builder.newDocument();
        Element root = doc.createElement("VdcConfigs");
        doc.appendChild(root);

        for (VdcConfig vdcConfig : newVdcConfigList) {
            Element vdcConfigNode = doc.createElement("VdcConfig");
            vdcConfigNode.setAttribute("id", vdcConfig.getId().toString());
            root.appendChild(vdcConfigNode);
            Method[] methods = vdcConfig.getClass().getDeclaredMethods();
            for (Method method : methods) {
                if (method.getName().contains("get") && !method.getName().contains("Id")) {
                    try {
                        Element fieldNode = doc.createElement("field");
                        Object name = method.getName().replace("get", "");
                        Object value = method.invoke(vdcConfig);
                        Object type = method.getReturnType().getName();
                        fieldNode.setAttribute("name", name.toString());
                        fieldNode.setAttribute("type", type.toString());
                        fieldNode.setAttribute("value", value==null?"":value.toString());
                        vdcConfigNode.appendChild(fieldNode);
                    } catch (Exception e) {
                        System.err.println("reflect fail: " + e);
                    }
                }
            }
        }

        try (FileOutputStream fos = new FileOutputStream(RecoverFileName);
             StringWriter sw = new StringWriter()) {
            Source source = new DOMSource(doc);
            Result result = new StreamResult(sw);
            Transformer xformer = TransformerFactory.newInstance().newTransformer();
            xformer.setOutputProperty(OutputKeys.INDENT, "yes");
            xformer.setOutputProperty(OutputKeys.ENCODING, "utf-8");
            xformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
            xformer.transform(source, result);
            byte[] encryptByte = geoEncryptionProvider.encrypt(sw.toString());
            fos.write(encryptByte);
            System.out.println(String.format("Dump into file: %s successfully", RecoverFileName));
            log.info("Dump into file: {} successfully", RecoverFileName);
        } catch (Exception e) {
            System.err.println("fail to write to file : " + e);
        }
    }

    private void verifyVdcConfigs(List<VdcConfig> vdcConfigs) {
        if (vdcConfigs == null)
            throw new RuntimeException("Null vdc config list");

        List<URI> vdcUrisFromDb = getVdcUrisFromDb();
        if (vdcConfigs.size() + 1 != vdcUrisFromDb.size()) {
            String errMsg = String.format("vdc config count from CF VdcOpLog (%d) " +
                    "should be 1 less than the vdc count from CF VirtualDataCenter (%d).",
                    vdcConfigs.size(), vdcUrisFromDb.size());
            log.error(errMsg);
            System.err.println(errMsg);
            System.err.println(REGEN_RECOVER_FILE_MSG);
            throw new RuntimeException("vdc count mismatch");
        }

        List<URI> vdcUrisFromConfig = getVdcUrisFromConfig(vdcConfigs);
        List<URI> failedVdcUris = new ArrayList<>(vdcUrisFromDb);
        failedVdcUris.removeAll(vdcUrisFromConfig);

        if (failedVdcUris.size() != 1) {
            String errMsg = String.format("Not all the vdc's from the vdc config list (%s) " +
                    " are found from CF VirtualDataCenter (%s)", vdcUrisFromConfig.toString(),
                    vdcUrisFromDb.toString());
            log.error(errMsg);
            System.err.println(errMsg);
            System.err.println(REGEN_RECOVER_FILE_MSG);
            throw new RuntimeException("vdc URI mismatch");
        }

        VirtualDataCenter vdc = _dbClient.queryObject(VirtualDataCenter.class,
                failedVdcUris.get(0));
        VirtualDataCenter.ConnectionStatus status = vdc.getConnectionStatus();
        if (status != VirtualDataCenter.ConnectionStatus.CONNECT_FAILED) {
            String errMsg = String.format("vdc %s should be in state CONNECT_FAILED but " +
                    "actually is %s.", failedVdcUris.get(0), vdc.getConnectionStatus());
            log.error(errMsg);
            System.err.println(errMsg);
            System.err.println(REGEN_RECOVER_FILE_MSG);
            throw new RuntimeException("wrong vdc connection status");
        }
    }

    private List<URI> getVdcUrisFromDb() {
        List<URI> vdcUriList = new ArrayList<>();
        List<URI> iterVdcUriList = _dbClient.queryByType(VirtualDataCenter.class, true);
        if (iterVdcUriList == null)
            throw new RuntimeException("Null vdc list");
        for (URI vdcUri : iterVdcUriList) {
            vdcUriList.add(vdcUri);
        }
        return vdcUriList;
    }

    private List<URI> getVdcUrisFromConfig(List<VdcConfig> vdcConfigs) {
        List<URI> vdcUriList = new ArrayList<>();
        for (VdcConfig vdcConfig : vdcConfigs) {
            vdcUriList.add(vdcConfig.getId());
        }
        return vdcUriList;
    }

    private List<VdcConfig> readRecoverBackupInfo() {
        List<VdcConfig> newVdcConfigList = new ArrayList<VdcConfig>();
        List<URI> ids = _dbClient.queryByType(VdcOpLog.class, true);
        VdcOpLog latestOp = null;
        for (URI id : ids) {
            if (latestOp == null) {
                latestOp = _dbClient.queryObject(VdcOpLog.class, id);
            } else {
                VdcOpLog thisOp = _dbClient.queryObject(VdcOpLog.class, id);
                if (thisOp.getCreationTime().getTimeInMillis() > latestOp
                        .getCreationTime().getTimeInMillis()) {
                    latestOp = thisOp;
                }
            }
        }
        if (latestOp != null) {
            byte[] vdcConfigInfo = latestOp.getVdcConfigInfo();
            try {
                List<VirtualDataCenter> vdcList = (List<VirtualDataCenter>) SerializerUtils
                        .deserialize(vdcConfigInfo);
                if (vdcList != null) {
                    for (VirtualDataCenter vdc : vdcList) {
                        newVdcConfigList.add(vdcConfHelper.toConfigParam(vdc));
                    }
                }
            } catch (Exception e) {
                log.error("error in recovervdcinfo: " + e);
            }

        }
        return newVdcConfigList;
    }

    /**
     * Read db secret key from zk and dump it into a specified file
     *
     * @param dumpFileName
     */
    public void dumpSecretKey(String dumpFileName) {
        dumpKeyToFile(dumpFileName);
        updateFilePermissions(dumpFileName);
    }

    private void dumpKeyToFile(String dumpFileName) {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(dumpFileName))) {
            SecretKey dbKey = encryptionProvider.getKey();
            SecretKey geodbKey = geoEncryptionProvider.getKey();
            if (dbKey == null || geodbKey == null) {
                throw new IllegalStateException("Key is null");
            }
            newZipEntry(zos, dbKey, KEY_DB);
            newZipEntry(zos, geodbKey, KEY_GEODB);
        } catch (IOException e) {
            System.err.println(String.format("Failed to write the key to file:%s\n Exception=%s", dumpFileName, e));
            log.error("Failed to write the key to file:{}", dumpFileName, e);
        }
    }

    private void newZipEntry(ZipOutputStream zos, SecretKey key, String name) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        ObjectOutputStream oos = new ObjectOutputStream(zos);
        oos.writeObject(key);
        zos.closeEntry();
    }

    private void updateFilePermissions(String dumpFileName) {
        File dumpFile = new File(dumpFileName);

        Set<PosixFilePermission> perms = new HashSet<PosixFilePermission>();
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);

        try {
            Files.setPosixFilePermissions(dumpFile.toPath(), perms);
        } catch (Exception e) {
            if (dumpFile.exists())
                dumpFile.delete();
            System.err.println(String.format("Failed to update file permission, Exception=%s", e));
            log.error("Failed to update file permission", e);            
        }
    }

    /**
     * Read db secret key from zk and dump it into a specified file
     *
     * @param restoreFileName
     */
    public void restoreSecretKey(String restoreFileName) {
        try (ZipFile zipFile = new ZipFile(restoreFileName)){
            SecretKey dbKey = readKey(zipFile, KEY_DB);
            SecretKey geodbKey = readKey(zipFile, KEY_GEODB);
            if (dbKey == null || geodbKey == null) {
                throw new IllegalStateException("Key is null");
            }

	    encryptionProvider.restoreKey(dbKey);
            geoEncryptionProvider.restoreKey(geodbKey);
        } catch (Exception e) {
            System.err.println(String.format("Failed to restore key, Exception=%s", e));
            log.error("Failed to restore key", e);
        }
    }

    private SecretKey readKey(ZipFile zipFile, String entryName) {
        try (InputStream ins = zipFile.getInputStream(zipFile.getEntry(entryName))){
            ObjectInputStream ois = new ObjectInputStream(ins);
            return (SecretKey) (ois.readObject());
        } catch (Exception e) {
            throw new IllegalStateException("Read key failed", e);
        }
    }
    
    /**
     * Show geodb black list
     */
    public Map<String, List<String>> getGeoBlacklist() {
        InternalDbClient geoDbClient = (InternalDbClient)ctx.getBean("geodbclient");
        return geoDbClient.getBlacklist();
    }

    /**
     * Remove geo blacklist for geo
     * 
     * @param vdcShortId
     */
    public void resetGeoBlacklist(String vdcShortId) {
        InternalDbClient geoDbClient = (InternalDbClient)ctx.getBean("geodbclient");
        List<URI> vdcList = geoDbClient.queryByType(VirtualDataCenter.class, true);
        for (URI vdcId : vdcList) {
            VirtualDataCenter vdc = geoDbClient.queryObject(VirtualDataCenter.class, vdcId);
            if (vdc.getShortId().equals(vdcShortId)) {
                System.out.println("Remove black list for vdc: " + vdcShortId);
                geoDbClient.removeVdcNodesFromBlacklist(vdc);
                break;
            }
        }
    }
    
    /**
     * Set geo blacklist
     * 
     * @param vdcShortId
     */
    public void setGeoBlacklist(String vdcShortId) {
        InternalDbClient geoDbClient = (InternalDbClient)ctx.getBean("geodbclient");
        List<URI> vdcList = geoDbClient.queryByType(VirtualDataCenter.class, true);
        for (URI vdcId : vdcList) {
            VirtualDataCenter vdc = geoDbClient.queryObject(VirtualDataCenter.class, vdcId);
            if (vdc.getShortId().equals(vdcShortId)) {
                System.out.println("Add black list for vdc: " + vdcShortId);
                geoDbClient.addVdcNodesToBlacklist(vdc);
                break;
            }
        }
    }

    /**
     *  check correctness of URI and serialize
     */
    public void checkDB() {
        int cfCount = 0;
        System.out.println("Start checking.");

        Collection<DataObjectType> cfList = TypeMap.getAllDoTypes();
        for (DataObjectType objType : cfList) {
            try {
                log.info("Check CF {}", objType.getDataObjectClass().getName());
                List<URI> uris = _dbClient.queryByType(objType.getDataObjectClass(), false);
                for(URI uri : uris) {
                    try {
                        _dbClient.queryObject(objType.getDataObjectClass(), uri);
                    } catch (Exception ex) {
                        String errMsg = String.format("Fail to query object for '%s' with err %s ", uri, ex.getMessage());
                        log.error(errMsg);
                        System.err.println(errMsg);
                    }
                    //TODO add checking for index
                }
                cfCount++;
            } catch (Exception ex) {
                log.error("Fail to check CF {}, with err", objType.getDataObjectClass().getName(), ex);
            }
        }

        System.out.println(String.format("End checking. Totally check %s cfs", cfCount));
    }
}
