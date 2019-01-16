/*******************************************************************************
 * Copyright (c) 2018 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.rsp.server.model;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jboss.tools.rsp.api.ServerManagementAPIConstants;
import org.jboss.tools.rsp.api.dao.Attributes;
import org.jboss.tools.rsp.api.dao.CreateServerResponse;
import org.jboss.tools.rsp.api.dao.DeployableReference;
import org.jboss.tools.rsp.api.dao.DeployableState;
import org.jboss.tools.rsp.api.dao.ServerHandle;
import org.jboss.tools.rsp.api.dao.ServerLaunchMode;
import org.jboss.tools.rsp.api.dao.ServerState;
import org.jboss.tools.rsp.api.dao.ServerType;
import org.jboss.tools.rsp.api.dao.util.CreateServerAttributesUtility;
import org.jboss.tools.rsp.eclipse.core.runtime.CoreException;
import org.jboss.tools.rsp.eclipse.core.runtime.IStatus;
import org.jboss.tools.rsp.eclipse.core.runtime.MultiStatus;
import org.jboss.tools.rsp.eclipse.core.runtime.NullProgressMonitor;
import org.jboss.tools.rsp.eclipse.core.runtime.Status;
import org.jboss.tools.rsp.eclipse.osgi.util.NLS;
import org.jboss.tools.rsp.launching.LaunchingCore;
import org.jboss.tools.rsp.secure.model.ISecureStorageProvider;
import org.jboss.tools.rsp.server.ServerCoreActivator;
import org.jboss.tools.rsp.server.model.internal.DaoUtilities;
import org.jboss.tools.rsp.server.model.internal.Server;
import org.jboss.tools.rsp.server.spi.model.IServerManagementModel;
import org.jboss.tools.rsp.server.spi.model.IServerModel;
import org.jboss.tools.rsp.server.spi.model.IServerModelListener;
import org.jboss.tools.rsp.server.spi.servertype.CreateServerValidation;
import org.jboss.tools.rsp.server.spi.servertype.IServer;
import org.jboss.tools.rsp.server.spi.servertype.IServerDelegate;
import org.jboss.tools.rsp.server.spi.servertype.IServerType;
import org.jboss.tools.rsp.server.spi.util.StatusConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerModel implements IServerModel {
	private static final Logger LOG = LoggerFactory.getLogger(ServerModel.class);

	private static final String SERVERS_DIRECTORY = "servers";

	private final Map<String, IServerType> serverTypes;
	private final Map<String, IServer> servers;
	private final Map<String, IServerDelegate> serverDelegates;
	private final List<IServerModelListener> listeners = new ArrayList<>();
	private final Set<String> approvedAttributeTypes = new HashSet<>();
	private final IServerManagementModel managementModel;

	public ServerModel(IServerManagementModel managementModel) {
		this(managementModel, 
				new HashMap<String, IServerType>(), new HashMap<String, IServer>(), new HashMap<String, IServerDelegate>());
	}

	/** for testing purposes **/
	protected ServerModel(IServerManagementModel managementModel, 
			Map<String, IServerType> serverTypes, Map<String, IServer> servers, Map<String, IServerDelegate> delegates) {
		this.serverTypes = serverTypes;
		this.servers = servers;
		this.serverDelegates = delegates;
		this.managementModel = managementModel;

		// Server attributes must be one of the following types
		approvedAttributeTypes.add(ServerManagementAPIConstants.ATTR_TYPE_INT);
		approvedAttributeTypes.add(ServerManagementAPIConstants.ATTR_TYPE_BOOL);
		approvedAttributeTypes.add(ServerManagementAPIConstants.ATTR_TYPE_STRING);
		// List must be List<String>
		approvedAttributeTypes.add(ServerManagementAPIConstants.ATTR_TYPE_LIST);
		// Map must be Map<String, String>
		approvedAttributeTypes.add(ServerManagementAPIConstants.ATTR_TYPE_MAP);
	}
	
	@Override
	public ISecureStorageProvider getSecureStorageProvider() {
		return managementModel.getSecureStorageProvider();
	}
	
	@Override
	public void addServerModelListener(IServerModelListener l) {
		listeners.add(l);
	}

	public void removeServerModelListener(IServerModelListener l) {
		listeners.remove(l);
	}

	@Override
	public void addServerType(IServerType type) {
		if( type != null && type.getId() != null ) {
			serverTypes.put(type.getId(), type);
		}
	}
	
	@Override
	public void addServerTypes(IServerType[] types) {
		if (types == null) {
			return;
		}
		
		for (IServerType type : types) {
			addServerType(type);
		}
	}

	@Override
	public void removeServerType(IServerType type) {
		if( type != null && type.getId() != null ) {
			serverTypes.remove(type.getId());
		}
	}
	
	@Override
	public void removeServerTypes(IServerType[] types) {
		if (types == null) {
			return;
		}
		
		for (IServerType type : types) {
			removeServerType(type);
		}
	}

	@Override
	public IServer getServer(String id) {
		return servers.get(id);
	}
	
	@Override
	public Map<String, IServer> getServers() {
		return Collections.unmodifiableMap(servers);
	} 
	
	@Override
	public void saveServers() throws CoreException {
		for (IServer server : getServers().values()) {
			server.save(new NullProgressMonitor());
		}
	}
	
	@Override
	public void loadServers() throws CoreException {
		File data = LaunchingCore.getDataLocation();
		File servers = new File(data, SERVERS_DIRECTORY);
		loadServers(servers);
	}

	public void loadServers(File folder) {
		if (!folder.exists()) {
			return;
		}
		for (File serverFile: folder.listFiles()) {
			Server server = loadServer(serverFile);
			if( server != null )
				addServer(server, server.getDelegate());

		}
	}

	private Server loadServer(File serverFile) {
		Server server = new Server(serverFile, managementModel);
		try {
			server.load(new NullProgressMonitor());
			String tid = server.getTypeId();
			IServerType st = getIServerType(tid);
			if( st != null ) {
				server.setServerType(st);
				server.setDelegate(st.createServerDelegate(server));
			}
			
			if( server.getServerType() == null ) {
				String typeId = server.getAttribute(Server.TYPE_ID, (String)null);
				if( typeId == null ) {
					log(new Exception(
							"Unable to load server from file " + serverFile.getAbsolutePath() + "; server type is missing or null."));
				} else if( createServerTypeDAO(typeId) == null ) {
					log(new Exception(
							"Unable to load server from file " + serverFile.getAbsolutePath() + "; server type " + typeId + " is not found in model."));
				}
				return null;
			} else {
				return server;
			}
		} catch(CoreException ce) {
			log(new Exception("Unable to load server from file " + serverFile.getAbsolutePath(), ce));
			return null;
		}
	}
	
	private void log(Exception e) {
		LOG.error(e.getMessage(), e);
	}
	
	@Override
	public CreateServerResponse createServer(String serverType, String id, Map<String, Object> attributes) {
		try {
			return createServerUnprotected(serverType, id, attributes);
		} catch(CoreException e) {
			return new CreateServerResponse(StatusConverter.convert(e.getStatus()), Collections.emptyList());
		} catch(Exception e) {
			IStatus s = new Status(IStatus.ERROR, ServerCoreActivator.BUNDLE_ID, 
					"An unexpected error occurred", e);
			return new CreateServerResponse(StatusConverter.convert(s), null);
		}
	}
	

	private CreateServerResponse createServerUnprotected(String serverType, String id, Map<String, Object> attributes) throws CoreException {
		if( servers.get(id) != null) {
			throw new CoreException(new Status(IStatus.ERROR, ServerCoreActivator.BUNDLE_ID, 
					"Server with id " + id + " already exists."));
		}
		IServerType type = serverTypes.get(serverType);
		if( type == null ) {
			return new CreateServerResponse(createDaoErrorStatus("Server Type " + serverType + " not found"), 
					Collections.emptyList());
		}
		IStatus validAttributes = validateAttributes(type, attributes);
		if( !validAttributes.isOK()) {
			return new CreateServerResponse(StatusConverter.convert(validAttributes), 
					getInvalidAttributeKeys(validAttributes));
		}
		
		Server server = createServer2(type, id, attributes);
		IServerDelegate del = server.getDelegate();
		CreateServerValidation valid = del.validate();
		if( !valid.getStatus().isOK()) {
			return valid.toDao();
		}
		addServer(server, del);
		server.save(new NullProgressMonitor());
		return valid.toDao();
	}
	
	private IStatus validateAttributes(IServerType type, Map<String, Object> map) {
		MultiStatus multiStatus = new MultiStatus(ServerCoreActivator.BUNDLE_ID, 0, 
				NLS.bind("There are missing/invalid required attributes for server type {0}", type), null);
		Attributes attr = type.getRequiredAttributes();
		CreateServerAttributesUtility util = new CreateServerAttributesUtility(attr);
		Set<String> required = util.listAttributes();
		for (String attrKey : required) {
			String attributeType = util.getAttributeType(attrKey);
			validateAttribute(attrKey, map, attributeType , multiStatus);
		}
		return multiStatus;
	}

	private void validateAttribute(String attrKey, Map<String, Object> attributeValues, String attributeType, MultiStatus multiStatus) {
		Object value = attributeValues.get(attrKey);
		if (value == null) {
			multiStatus.add(
					new Status(IStatus.ERROR, attrKey, NLS.bind("Attribute {0} must not be null", attrKey)));
		} else {
			Class<?> actualType = value.getClass();
			Class<?> expectedType = DaoUtilities.getAttributeTypeClass(attributeType);
			if (!actualType.equals(expectedType)) {
				// Something's different than expectations based on json transfer
				// Try to convert it
				Object converted = convertJSonTransfer(value, expectedType);
				if (converted == null) {
					multiStatus.add(new Status(IStatus.ERROR, attrKey,
							NLS.bind("Attribute {0} must be of type {1} but is of type {2}",
									new String[] { attrKey, expectedType.getName(), actualType.getName() })));
				} else {
					attributeValues.put(attrKey, converted);
				}
			} 
			if (String.class.equals(expectedType) 
					&& ((String) value).trim().isEmpty()) {
					multiStatus.add(new Status(IStatus.ERROR, attrKey,
							NLS.bind("Attribute {0} must not be empty", attrKey)));
			}
		}
	}

	private Object convertJSonTransfer(Object value, Class<?> expected) {
		// TODO check more things here for errors in the transfer
		if( Integer.class.equals(expected) && Double.class.equals(value.getClass())) {
			return Integer.valueOf(((Double)value).intValue());
		}
		return null;
	}

	private List<String> getInvalidAttributeKeys(IStatus status) {
		return Arrays.stream(status.getChildren())
			.map(IStatus::getPlugin)
			.collect(Collectors.toList());
	}

	private Server createServer2(IServerType serverType, String id, Map<String, Object> attributes) {
		File data = LaunchingCore.getDataLocation();
		File serversDirectory = new File(data, SERVERS_DIRECTORY);
		if( !serversDirectory.exists()) {
			serversDirectory.mkdirs();
		}
		// TODO check for duplicates
		File serverFile = new File(serversDirectory, id);
		return new Server(serverFile, serverType, id, attributes, managementModel);
	}

	protected void addServer(IServer server, IServerDelegate del) {
		servers.put(server.getId(), server);
		serverDelegates.put(server.getId(), del);
		fireServerAdded(server, del.getServerState());
	}

	private void fireServerAdded(IServer server, ServerState state) {
		for( IServerModelListener l : listeners ) {
			l.serverAdded(toHandle(server), state);
		}
	}

	@Override
	public void fireServerProcessTerminated(IServer server, String processId) {
		for( IServerModelListener l : listeners ) {
			l.serverProcessTerminated(toHandle(server), processId);
		}
	}

	@Override
	public void fireServerProcessCreated(IServer server, String processId) {
		for( IServerModelListener l : listeners ) {
			l.serverProcessCreated(toHandle(server), processId);
		}
	}

	@Override
	public void fireServerStreamAppended(IServer server, String processId, int streamType, String text) {
		for( IServerModelListener l : listeners ) {
			l.serverProcessOutputAppended(toHandle(server), processId, streamType, text);
		}
	}
	
	@Override
	public void fireServerStateChanged(IServer server, ServerState state) {
		for( IServerModelListener l : listeners ) {
			l.serverStateChanged(toHandle(server), state);
		}
	}
	
	private void fireServerRemoved(IServer server) {
		for( IServerModelListener l : listeners ) {
			l.serverRemoved(toHandle(server));
		}
	}
	
	private ServerHandle toHandle(IServer s) {
		String typeId = s.getTypeId();
		return new ServerHandle(s.getId(), createServerTypeDAO(typeId));
	}
	
	@Override
	public boolean removeServer(IServer toRemove) {
		if( toRemove == null || toRemove.getId() == null) {
			return false;
		}
		String serverId = toRemove.getId();
		servers.remove(serverId);
		IServerDelegate s = serverDelegates.get(serverId);
		serverDelegates.remove(serverId);
		s.dispose();
		fireServerRemoved(toRemove);
		try {
			toRemove.delete();
		} catch (CoreException e) {
			//log silently. Looks like nothing crucial
			log(e);
		}
		return true;
	}
	
	@Override
	public ServerHandle[] getServerHandles() {
		Set<String> serverKeys = servers.keySet();
		ArrayList<ServerHandle> handles = new ArrayList<>();
		for( String serverKey : serverKeys ) {
			String id = serverKey;
			String type = servers.get(id).getTypeId();
			handles.add(new ServerHandle(id,  createServerTypeDAO(type)));
		}
		return handles.toArray(new ServerHandle[handles.size()]);
	}
	
	private ServerType createServerTypeDAO(String typeId) {
		IServerType st = serverTypes.get(typeId);
		if( st == null )
			return null;
		return new ServerType(typeId, st.getName(), st.getDescription());
	}
	
	@Override
	public IServerType getIServerType(String typeId) {
		return serverTypes.get(typeId);
	}
	
	@Override
	public ServerType[] getServerTypes() {
		Set<String> types = serverTypes.keySet();
		ArrayList<String> types2 = new ArrayList<>(types);
		Collections.sort(types2);
		ArrayList<ServerType> ret = new ArrayList<>();
		for( String t : types2 ) {
			IServerType type = serverTypes.get(t);
			ret.add(new ServerType(t, type.getName(), type.getDescription()));
		}
		return ret.toArray(new ServerType[ret.size()]);
	}

	@Override
	public ServerType[] getAccessibleServerTypes() {
		List<ServerType> free = new ArrayList<>();
		List<ServerType> all = new ArrayList<>();
		
		Set<String> types = serverTypes.keySet();
		ArrayList<String> types2 = new ArrayList<>(types);
		Collections.sort(types2);
		for( String t : types2 ) {
			// Always add to 'all',   add to 'free' if type does not require secure storage
			IServerType type = serverTypes.get(t);
			ServerType st = new ServerType(t, type.getName(), type.getDescription());
			if( !hasSecureAttributes(type)) {
				free.add(st);
			}
			all.add(st);
		}
		
		if (all.size() > free.size()
				&& !hasPermissions()) {
			return free.toArray(new ServerType[free.size()]);
		}
		
		return all.toArray(new ServerType[all.size()]);
	}
	
	private boolean hasPermissions() {
		return managementModel.getSecureStorageProvider().getSecureStorage(true) != null;
	}

	private boolean hasSecureAttributes(IServerType type) {
		Attributes a = type.getRequiredAttributes();
		Attributes b = type.getOptionalAttributes();
		Set<String> all = new HashSet<>();
		all.addAll(a.getAttributes().keySet());
		all.addAll(b.getAttributes().keySet());
		for( Iterator<String> i = all.iterator(); i.hasNext(); ) {
			if( i.next().startsWith(IServerModel.SECURE_ATTRIBUTE_PREFIX)) 
				return true;
		}
		return false;
	}

	@Override
	public Attributes getRequiredAttributes(IServerType serverType) {
		Attributes ret = serverType == null ? null : serverType.getRequiredAttributes();
		return validateAttributes(ret, serverType);
	}
	
	@Override
	public Attributes getOptionalAttributes(IServerType serverType) {
		Attributes ret = serverType == null ? null : serverType.getOptionalAttributes();
		return validateAttributes(ret, serverType);
	}

	@Override
	public List<ServerLaunchMode> getLaunchModes(IServerType serverType) {
		ServerLaunchMode[] ret = serverType == null ? null : serverType.getLaunchModes();
		return ret == null ? null : Arrays.asList(ret);
	}
	
	@Override
	public Attributes getRequiredLaunchAttributes(IServerType serverType) {
		Attributes ret = serverType == null ? null : serverType.getRequiredLaunchAttributes();
		return validateAttributes(ret, serverType);
	}
	
	@Override
	public Attributes getOptionalLaunchAttributes(IServerType serverType) {
		Attributes ret = serverType == null ? null : serverType.getOptionalLaunchAttributes();
		return validateAttributes(ret, serverType);
	}

	private Attributes validateAttributes(Attributes ret, IServerType serverType) {
		if( ret != null ) {
			CreateServerAttributesUtility util = new CreateServerAttributesUtility(ret);
			Set<String> all = util.listAttributes();
			for( String all1 : all ) {
				String attrType = util.getAttributeType(all1);
				if( !approvedAttributeTypes.contains(attrType)) {
					LOG.error("Extension for servertype {} is invalid and requires an attribute of an invalid type.", serverType);
					util.removeAttribute(all1);
				}
			}
			return util.toPojo();
		}
		return null;
	}

	@Override
	public IStatus addDeployable(IServer server, DeployableReference reference) {
		IServerDelegate s = serverDelegates.get(server.getId());
		if( s == null ) {
			return new Status(IStatus.ERROR, ServerCoreActivator.BUNDLE_ID, 
					"Server " + server.getId() + " not found.");
		}
		IStatus canAdd = s.canAddDeployable(reference);
		if(!canAdd.isOK()) {
			return canAdd;
		}
		return s.getServerPublishModel().addDeployable(reference);
	}

	@Override
	public IStatus removeDeployable(IServer server, DeployableReference reference) {
		IServerDelegate s = serverDelegates.get(server.getId());
		if( s == null ) {
			return new Status(IStatus.ERROR, ServerCoreActivator.BUNDLE_ID, 
					"Server " + server.getId() + " not found.");
		}
		IStatus canRemove = s.canRemoveDeployable(reference);
		if (!canRemove.isOK()) {
			return canRemove;
		}
		return s.getServerPublishModel().removeDeployable(reference);
	}

	@Override
	public List<DeployableState> getDeployables(IServer server) {
		IServerDelegate s = serverDelegates.get(server.getId());
		if( s != null ) {
			return s.getServerPublishModel().getDeployableStates();
		}
		return new ArrayList<>();
	}

	@Override
	public IStatus publish(IServer server, int kind) throws CoreException {
		IServerDelegate s = serverDelegates.get(server.getId());
		if( s != null ) {
			IStatus canPublish = s.canPublish();
			if( canPublish != null && canPublish.isOK()) {
				return s.publish(kind);
			} else {
				return new Status(IStatus.ERROR, ServerCoreActivator.BUNDLE_ID, 
						"Server " + server.getId() + " is not in a state that can be published to: " + canPublish.getMessage());
			}
		}
		return Status.CANCEL_STATUS;
	}

	private org.jboss.tools.rsp.api.dao.Status createDaoErrorStatus(String message) {
		return new org.jboss.tools.rsp.api.dao.Status(IStatus.ERROR, ServerCoreActivator.BUNDLE_ID, message, null);
	}
	
}
