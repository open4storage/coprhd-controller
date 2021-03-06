/**
* Copyright 2015 EMC Corporation
* All Rights Reserved
 */
/**
 *  Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */

package com.emc.storageos.security.password;

import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.coordinator.client.service.CoordinatorClient;
import com.emc.storageos.coordinator.client.service.DistributedDataManager;
import com.emc.storageos.coordinator.common.impl.ZkPath;
import org.apache.curator.framework.recipes.locks.InterProcessLock;
import com.emc.storageos.security.exceptions.SecurityException;

import javax.servlet.http.HttpServletRequest;

/**
 * The class to handle invalid login attempts. 
 * If the invalid login attempts from the same IP exceed the configured parameter (default 10) the IP will be blocked for
 * a configured life time (default 10 minutes). After that time the IP will be cleared to allow logins from that IP to proceed.
 * Successful login from a client IP will clear the record in ZK for that IP.  
 *
 */
public class InvalidLoginManager {
    private static final Logger _log = LoggerFactory.getLogger(InvalidLoginManager.class);

    private static final int MAX_AUTHN_LOGIN_ATTEMPTS_COUNT = 10;
    private static final int MAX_AUTHN_LOGIN_ATTEMPTS_NODE_COUNT = 5000;
    private static final int MAX_AUTHN_LOGIN_ATTEMPTS__LIFE_TIME_IN_MINS = 10;
    private static final int CLEANUP_THREAD_INITIAL_DELAY_IN_MINS = 10;
    private static final long MIN_TO_MSECS = 60 * 1000;
    private static final String INVALID_LOGIN_CLEANER_LOCK = "invalid_login_cleaner_lock";
    private static final String INVALID_LOGIN_VERSION = "_2.0";
    public static final String OLD_PASSWORD_INVALID_ERROR = "Old password is invalid";
    private final ScheduledExecutorService _invalidLoginCleanupExecutor = Executors.newScheduledThreadPool(1);

    
    private CoordinatorClient _coordinator;
    protected DistributedDataManager _distDataManager;
    
    protected int _maxAuthnLoginAttemtsCount  = MAX_AUTHN_LOGIN_ATTEMPTS_COUNT;
    protected int _maxAuthnLoginAttemtsLifeTimeInMins = MAX_AUTHN_LOGIN_ATTEMPTS__LIFE_TIME_IN_MINS;
    protected int _cleanupThreadInitialDelay = CLEANUP_THREAD_INITIAL_DELAY_IN_MINS;

    public int getMaxAuthnLoginAttemtsCount() {
        return _maxAuthnLoginAttemtsCount;
    }

    public void setMaxAuthnLoginAttemtsCount(int maxAuthnLoginAttemtsCount) {
        this._maxAuthnLoginAttemtsCount = maxAuthnLoginAttemtsCount;
    }

    public int getMaxAuthnLoginAttemtsLifeTimeInMins() {
        return _maxAuthnLoginAttemtsLifeTimeInMins;
    }

    public void setCleanupThreadInitialDelay(int cleanupThreadInitialDelay) {
        this._cleanupThreadInitialDelay = cleanupThreadInitialDelay;
    }

    public int getCleanupThreadInitialDelay() {
        return _cleanupThreadInitialDelay;
    }

    public void setMaxAuthnLoginAttemtsLifeTimeInMins( int maxAuthnLoginAttemtsLifeTimeInMins) {
        this._maxAuthnLoginAttemtsLifeTimeInMins = maxAuthnLoginAttemtsLifeTimeInMins;
    }

    public void setCoordinator(CoordinatorClient coordinator) {
        _coordinator = coordinator;
        _distDataManager = _coordinator.createDistributedDataManager(ZkPath.AUTHN.toString(), MAX_AUTHN_LOGIN_ATTEMPTS_NODE_COUNT);
        if (null == _distDataManager) {
            throw SecurityException.fatals.coordinatorNotInitialized();
        }
    }

    /**
     * Check if the client IP is blocked.
     * It is blocked if there are too many invalid logins from that IP. The default value for this parameter is 10.
     * @brief Checks if the client IP is blocked
     * @param clientIP Client IP address to be used to check the number of invalid login attempts from that IP
     * @return true if the provided client IP is blocked because of too many invalid login attempts
     */
    public boolean isTheClientIPBlocked(String clientIP) {
        try {
            if (null != clientIP && !clientIP.isEmpty()) {
                String zkPath = getZkPath(clientIP);
                InvalidLogins invLogins = (InvalidLogins)_distDataManager.getData(zkPath, false);
                if (null != invLogins) {
                    if (isClientInvalidRecordExpired(invLogins)) {
                        removeInvalidRecord(clientIP);
                        return false;
                    }
                    if (invLogins.getLoginAttempts() < _maxAuthnLoginAttemtsCount) {
                        return false;
                    }
                } else {
                    return false;
                }
                // This IP is blocked, too many invalid logins from that IP. 
                // It will be cleared after MAX_AUTHN_LOGIN_ATTEMPTS__LIFE_TIME_IN_MINS from the last invalid login.
                _log.error("The client IP is blocked, too many error logins from that IP: {}", clientIP);
            } else {
                _log.error("The provided client IP is null or empty.");
            }
        } catch (Exception ex) {
            _log.error("Failed to check the error login count", ex);
        }
        return true;  
    }
   
    private boolean isClientInvalidRecordExpired(InvalidLogins invLogins) {
            if (null != invLogins && (getCurrentTimeInMins() - invLogins.getLastAccessTime() ) > _maxAuthnLoginAttemtsLifeTimeInMins ) {
                return true;
            }
        return false;
    }

    /**
     * This is NOOP if the client IP is not in ZK,
     * if exists, get a lock INVALID_LOGIN_CLEANER_LOCK and then remove the record
     * @brief Remove the client IP from the  invalid login records list
     * @param clientIP The client IP to be removed from the invalid login records list
     */
    public void removeInvalidRecord(String clientIP) {
        try {
            if (!StringUtils.isBlank(clientIP)) {

                //check if zk contains the IP.
                InvalidLogins invLogins = (InvalidLogins)_distDataManager.getData(getZkPath(clientIP), false);
                if (null == invLogins) {
                    _log.debug("{} doesn't in zk, return from removeInvalidRecord", clientIP);
                    return;
                }

                // zk contains the ClientIP, start removing.
                InterProcessLock lock = null;
                try {
                    lock = _coordinator.getLock(INVALID_LOGIN_CLEANER_LOCK);
                    lock.acquire();
                    _log.info("Got ZK lock to remove a record created for invalid logins from this client IP: {}", clientIP);
                    String zkPath = getZkPath(clientIP);
                    _distDataManager.removeNode(zkPath);
                    _log.info("Removed an invalid record entry: {}", zkPath);
                } catch (Exception ex) {
                    _log.warn("Unexpected exception during db maintenance", ex);
                } finally {
                    if (lock != null) {
                        try {
                            lock.release();
                        } catch (Exception ex) {
                            _log.warn("Unexpected exception unlocking the invalid login lock", ex);
                        }
                    }
                }
            } else {
                _log.warn("Trying to remove an invalid record entry, the provided client IP is null or empty");
            }
        } catch (Exception ex) {
            _log.error("Unexpected exception", ex);
        }
    }
   
    /**
     * Generate version specific ZK node path like /authservice/192.168.2.1_2.0 
     * @param clientIP
     * @return generated ZK node name or null if the provided clientIP is null or empty
     */
    private String getZkPath(String clientIP) {
        if (null != clientIP && !clientIP.isEmpty()) {
            return ZkPath.AUTHN.toString() + "/" + clientIP + INVALID_LOGIN_VERSION;
        }
        return null;
    }
    /**
     * The client failed to login. If an invalid login record exists for that client, increment the error count of that record.
     * If that record does nor exists, create new entry.
     * @brief Update the invalid login record for this client
     * @param clientIP
     */
    public void markErrorLogin(String clientIP) {
        if (null != clientIP && !clientIP.isEmpty()) {
            String zkPath = getZkPath(clientIP);
            InterProcessLock lock = null;
            try {
                // Update the DB record. Get the lock first
                lock = _coordinator.getLock(INVALID_LOGIN_CLEANER_LOCK);
                lock.acquire();
                _log.debug("Got a lock for updating the ZK");
                InvalidLogins invLogins = (InvalidLogins)_distDataManager.getData(zkPath, false);
                if (null == invLogins) {
                    // New entry for this invalid login
                    _distDataManager.createNode(zkPath, false);
                    invLogins = new InvalidLogins(getCurrentTimeInMins(), 1);
                    _log.debug("Creating new record in the ZK for the client {}",clientIP);
                } else {
                    invLogins.incrementErrorLoginCount();
                }
                // Update the last invalid login time stamp.
                invLogins.setLastAccessTime(getCurrentTimeInMins());
                _log.debug("Updating the record in the ZK for the client {}",clientIP);
                _distDataManager.putData(zkPath, invLogins);
            } catch (Exception ex) {
                _log.error("Exception for the clientIP {} ", clientIP, ex);
            } finally {
                if (lock != null) {
                    try {
                        lock.release();
                    } catch (Exception ex) {
                        _log.error("Unexpected exception unlocking the lock for updating the ZK", ex);
                    }
                }
            }
        } else {
            _log.error("The provided clientIP is null or empty ");
        }
        return;
    }
    
    /**
     * @brief Get the current time in minutes 
     * @return The current time in minutes
     */
    protected long getCurrentTimeInMins() {
        return System.currentTimeMillis() / (MIN_TO_MSECS);
    }

    /**
     * Walk through the list of records for Invalid Login Attempts, and for each record check the expiration time.
     * If the record is expired, then delete that record.
     * @brief Invalid Login Records cleanup 
     * 
     */
    protected void invLoginCleanup() {
        String zkRoot = ZkPath.AUTHN.toString();
        StringBuilder visitedRecords = new StringBuilder("");
        StringBuilder removedRecords = new StringBuilder("");
        int deletedCount = 0;
        try {
            // Wait for random number of seconds to avoid all nodes in the cluster to access ZK at the same time
            Random random = new Random(System.currentTimeMillis());
            long sleepInterval = (int)(random.nextDouble() * 100000); // interval is between 0 - 100,000 milliseconds
            _log.debug("Sleeping for milliseconds: {}", sleepInterval);
            Thread.sleep(sleepInterval);
            List<String> recordNames = _distDataManager.getChildren(zkRoot);
            if (null != recordNames) {
                for (String clientIP : recordNames) {
                    String zkPath = zkRoot + "/" + clientIP;
                    visitedRecords.append(zkPath);
                    visitedRecords.append("; ");
                    InvalidLogins invLogins = (InvalidLogins)_distDataManager.getData(zkPath, false);
                    if (isClientInvalidRecordExpired(invLogins)) {
                        // The invalid login record is expired. Delete it. We already have a lock.
                        _distDataManager.removeNode(zkPath);
                        removedRecords.append(zkPath);
                        removedRecords.append("; ");
                        _log.debug("Invalid login record for the client IP {} is removed", clientIP);
                        deletedCount++;
                    }
                }
                if (deletedCount > 0) {
                    _log.info("Invalid login records cleanup: deleted {} records", deletedCount);
                }
                _log.debug("Invalid login records cleanup: visited records {}", visitedRecords);
                _log.debug("Invalid login records cleanup: removed records {}", removedRecords);
            }
        } catch (Exception ex) {
            _log.warn("Unexpected exception during db maintenance", ex);
        }
    }
    
    /**
     * Initialize the background task to be run every hour.
     * At each run that task will walk through the Invalid Login records and clean all expired records.
     * @brief Initialize the background thread to clean up the Invalid Login records
     */
    public void init() {
        _invalidLoginCleanupExecutor.scheduleWithFixedDelay(
        new InvalidLoginCleaner(), _cleanupThreadInitialDelay, _maxAuthnLoginAttemtsLifeTimeInMins, TimeUnit.MINUTES);
        _log.info("Max invalid login attempts from the same client IP: {}", _maxAuthnLoginAttemtsCount);
        _log.info("Life time in minutes of invalid login records for a client IP: {}", _maxAuthnLoginAttemtsLifeTimeInMins);
        _log.info("Cleanup thread initial delay: {}", _cleanupThreadInitialDelay);
    }
    
    /**
     * The class to implement the Invalid Login records cleanup thread 
     *
     */
    private class InvalidLoginCleaner implements Runnable {

        public void run() {
            InterProcessLock lock = null;
            try {
                _log.debug("Starting invalid login cleanup executor ...");
                lock = _coordinator.getLock(INVALID_LOGIN_CLEANER_LOCK);
                lock.acquire();
                _log.debug("Got a lock for invalid login cleanup thread");
                invLoginCleanup();
            } catch (Exception ex) {
                _log.warn("Unexpected exception during db maintenance", ex);
            } finally {
                if (lock != null) {
                    try {
                        lock.release();
                    } catch (Exception ex) {
                        _log.warn("Unexpected exception unlocking the lock for invalid login records cleanup thread", ex);
                    }
                }
            }
        }
    }

    /**
     * 
     * @brief Shutdown the background thread
     */
    public void shutdown() {
        if (null != _invalidLoginCleanupExecutor) {
            try {
                _invalidLoginCleanupExecutor.shutdown();
                _invalidLoginCleanupExecutor.awaitTermination(15, TimeUnit.MINUTES);
            } catch (Exception ex) {
                _log.error("Failed to stop the background thread.", ex);
            } finally {
                _invalidLoginCleanupExecutor.shutdownNow();
            }
        }
        if (_distDataManager != null) {
            _distDataManager.close();
        }
    }

    public static String getClientIP(HttpServletRequest req) {
        if (req == null) {
            return null;
        }

        String srcHost = req.getHeader("X-Real-IP");
        if (srcHost == null) {
            srcHost = req.getRemoteAddr();
        }
        return srcHost;
    }
}
