/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.npm.portlet.extender;

import com.liferay.portal.kernel.json.JSONArray;
import com.liferay.portal.kernel.json.JSONFactory;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.portlet.bridges.mvc.MVCPortlet;
import com.liferay.portal.kernel.util.StringUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;

import java.net.URL;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.portlet.Portlet;
import javax.portlet.RenderRequest;
import javax.portlet.RenderResponse;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.namespace.extender.ExtenderNamespace;
import org.osgi.util.tracker.BundleTracker;
import org.osgi.util.tracker.BundleTrackerCustomizer;
import org.osgi.util.tracker.ServiceTracker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Jesse Rao
 */
public class NPMPortletExtender implements BundleActivator {

	@Override
	public void start(BundleContext context) throws Exception {
		_jsonFactoryTracker = new ServiceTracker<JSONFactory, JSONFactory>(
			context, JSONFactory.class, null) {

			@Override
			public JSONFactory addingService(
				ServiceReference<JSONFactory> reference) {

				if (_bundleTracker != null) {
					return null;
				}

				JSONFactory jsonFactory = context.getService(reference);

				_bundleTracker = new BundleTracker<>(
					context, Bundle.ACTIVE,
					new BundleTrackerCustomizer<ServiceRegistration<?>>() {

						@Override
						public ServiceRegistration<?> addingBundle(
							Bundle bundle, BundleEvent event) {

							if (!_optIn(bundle)) {
								return null;
							}

							URL jsonURL = bundle.getEntry(
								"META-INF/resources/package.json");

							try (InputStream inputStream =
									jsonURL.openStream()) {

								String jsonString = StringUtil.read(
									inputStream);

								JSONObject packageJSONObject =
									jsonFactory.createJSONObject(jsonString);

								final String name = packageJSONObject.getString(
									"name");
								final String version =
									packageJSONObject.getString("version");

								Dictionary<String, Object> properties =
									new Hashtable<>();

								properties.put("javax.portlet.name", name);

								JSONObject portletJSONObject =
									packageJSONObject.getJSONObject("portlet");

								_addPortletProperties(
									properties, portletJSONObject);

								BundleContext bundleContext =
									bundle.getBundleContext();

								ServiceRegistration<?> serviceRegistration =
									bundleContext.registerService(
										new String[] {Portlet.class.getName()},
										new NPMPortlet(name, version),
										properties);

								return serviceRegistration;
							}
							catch (Exception e) {
								_logger.error(e.getMessage());
							}

							return null;
						}

						@Override
						public void modifiedBundle(
							Bundle bundle, BundleEvent event,
							ServiceRegistration<?> registration) {
						}

						@Override
						public void removedBundle(
							Bundle bundle, BundleEvent event,
							ServiceRegistration<?> registration) {

							registration.unregister();
						}

					});

				_bundleTracker.open();

				return jsonFactory;
			}

			@Override
			public void removedService(
				ServiceReference<JSONFactory> reference, JSONFactory service) {

				_bundleTracker.close();

				_bundleTracker = null;
			}

		};

		_jsonFactoryTracker.open();
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		_jsonFactoryTracker.close();
	}

	private void _addPortletProperties(
		Dictionary<String, Object> properties, JSONObject portletJSONObject) {

		if (portletJSONObject == null) {
			return;
		}

		Iterator<String> keys = portletJSONObject.keys();

		while (keys.hasNext()) {
			String key = keys.next();

			Object value = portletJSONObject.get(key);

			if (value instanceof JSONObject) {
				String stringValue = value.toString();

				properties.put(key, stringValue);
			}
			else if (value instanceof JSONArray) {
				JSONArray array = (JSONArray)value;

				List<String> values = new ArrayList<>();

				for (int i = 0; i < array.length(); i++) {
					Object object = array.get(i);

					values.add(object.toString());
				}

				properties.put(key, values.toArray(new String[0]));
			}
			else {
				properties.put(key, value);
			}
		}
	}

	private boolean _optIn(Bundle bundle) {
		BundleWiring bundleWiring = bundle.adapt(BundleWiring.class);

		List<BundleWire> bundleWires = bundleWiring.getRequiredWires(
			ExtenderNamespace.EXTENDER_NAMESPACE);

		for (BundleWire bundleWire : bundleWires) {
			BundleCapability bundleCapability = bundleWire.getCapability();

			Map<String, Object> attributes = bundleCapability.getAttributes();

			Object value = attributes.get(ExtenderNamespace.EXTENDER_NAMESPACE);

			if ((value != null) && value.equals("liferay.npm.portlet")) {
				return true;
			}
		}

		return false;
	}

	private static final Logger _logger = LoggerFactory.getLogger(
		NPMPortletExtender.class);

	private BundleTracker<ServiceRegistration<?>> _bundleTracker;
	private ServiceTracker<JSONFactory, JSONFactory> _jsonFactoryTracker;

	private static class NPMPortlet extends MVCPortlet {

		public NPMPortlet(String name, String version) {
			_name = name;
			_version = version;
		}

		@Override
		public void render(RenderRequest request, RenderResponse response) {
			try {
				PrintWriter writer = response.getWriter();

				writer.print("<div id=\"npm-portlet-");
				writer.print(response.getNamespace());
				writer.println("\"></div>");
				writer.println("<script type=\"text/javascript\">");
				writer.print("Liferay.Loader.require(\"");
				writer.print(_name);
				writer.print("@");
				writer.print(_version);
				writer.println("\", function(module) {");
				writer.println("module.default({");
				writer.print("portletNamespace: \"");
				writer.print(response.getNamespace());
				writer.println("\",");
				writer.print("contextPath: \"");
				writer.print(request.getContextPath());
				writer.println("\",");
				writer.print("portletElementId: \"npm-portlet-");
				writer.print(response.getNamespace());
				writer.println("\"});});");
				writer.println("</script>");

				writer.flush();
			}
			catch (IOException ioe) {
				_logger.error(ioe.getMessage());
			}
		}

		private final String _name;
		private final String _version;

	}

}