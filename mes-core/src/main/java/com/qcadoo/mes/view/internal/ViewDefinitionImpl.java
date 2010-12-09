/**
 * ***************************************************************************
 * Copyright (c) 2010 Qcadoo Limited
 * Project: Qcadoo MES
 * Version: 0.2.0
 *
 * This file is part of Qcadoo.
 *
 * Qcadoo is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation; either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 * ***************************************************************************
 */

package com.qcadoo.mes.view.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.qcadoo.mes.api.TranslationService;
import com.qcadoo.mes.api.ViewDefinitionService;
import com.qcadoo.mes.model.DataDefinition;
import com.qcadoo.mes.model.HookDefinition;
import com.qcadoo.mes.view.ComponentPattern;
import com.qcadoo.mes.view.ContainerPattern;
import com.qcadoo.mes.view.ViewDefinition;
import com.qcadoo.mes.view.ViewDefinitionState;
import com.qcadoo.mes.view.patterns.AbstractComponentPattern;

public final class ViewDefinitionImpl implements ViewDefinition {

    private final String name;

    private final String pluginIdentifier;

    private final DataDefinition dataDefinition;

    private final boolean menuAccessible;

    private final List<HookDefinition> postInitializeHooks = new ArrayList<HookDefinition>();

    private final List<HookDefinition> preInitializeHooks = new ArrayList<HookDefinition>();

    private final List<HookDefinition> preRenderHooks = new ArrayList<HookDefinition>();

    private final Set<String> jsFilePaths = new HashSet<String>();

    private final Map<String, ComponentPattern> patterns = new LinkedHashMap<String, ComponentPattern>();

    private final TranslationService translationService;

    public ViewDefinitionImpl(final String name, final String pluginIdentifier, final DataDefinition dataDefinition,
            final boolean menuAccessible, final TranslationService translationService) {
        this.name = name;
        this.dataDefinition = dataDefinition;
        this.pluginIdentifier = pluginIdentifier;
        this.menuAccessible = menuAccessible;
        this.translationService = translationService;
    }

    public void initialize() {
        List<ComponentPattern> list = getPatternsAsList(patterns.values());

        int lastNotInitialized = 0;

        while (true) {
            int notInitialized = 0;

            for (ComponentPattern pattern : list) {
                if (!pattern.initialize()) {
                    notInitialized++;
                }
            }

            if (notInitialized == 0) {
                break;
            }

            if (notInitialized == lastNotInitialized) {
                throw new IllegalStateException("There is cyclic dependency between components");
            }

            lastNotInitialized = notInitialized;
        }
    }

    @Override
    public Map<String, Object> prepareView(final Locale locale) {
        Map<String, Object> model = new HashMap<String, Object>();
        Map<String, Object> childrenModels = new HashMap<String, Object>();

        for (ComponentPattern componentPattern : patterns.values()) {
            childrenModels.put(componentPattern.getName(), componentPattern.prepareView(locale));
        }

        model.put(JSON_COMPONENTS, childrenModels);
        model.put(JSON_JS_FILE_PATHS, getJsFilePaths());

        model.put("hasDataDefinition", getDataDefinition() != null);

        try {
            JSONObject json = new JSONObject();
            JSONObject translations = new JSONObject();
            translations.put("backWithChangesConfirmation",
                    translationService.translate("commons.backWithChangesConfirmation", locale));
            json.put("translations", translations);
            model.put("jsOptions", json);
        } catch (JSONException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }

        return model;
    }

    @Override
    public JSONObject performEvent(final JSONObject object, final Locale locale) throws JSONException {
        ViewDefinitionState viewDefinitionState = new ViewDefinitionStateImpl();

        for (ComponentPattern cp : patterns.values()) {
            viewDefinitionState.addChild(cp.createComponentState());
        }

        callHooks(preInitializeHooks, viewDefinitionState, locale);

        viewDefinitionState.initialize(object, locale);

        for (ComponentPattern cp : patterns.values()) {
            ((AbstractComponentPattern) cp).updateComponentStateListeners(viewDefinitionState);
        }

        callHooks(postInitializeHooks, viewDefinitionState, locale);

        JSONObject eventJson = object.getJSONObject(JSON_EVENT);
        String eventName = eventJson.getString(JSON_EVENT_NAME);
        String eventComponent = eventJson.has(JSON_EVENT_COMPONENT) ? eventJson.getString(JSON_EVENT_COMPONENT) : null;
        JSONArray eventArgsArray = eventJson.has(JSON_EVENT_ARGS) ? eventJson.getJSONArray(JSON_EVENT_ARGS) : new JSONArray();
        String[] eventArgs = new String[eventArgsArray.length()];
        for (int i = 0; i < eventArgsArray.length(); i++) {
            eventArgs[i] = eventArgsArray.getString(i);
        }

        viewDefinitionState.performEvent(eventComponent, eventName, eventArgs);

        callHooks(preRenderHooks, viewDefinitionState, locale);

        return viewDefinitionState.render();
    }

    public void registerViews(final ViewDefinitionService viewDefinitionService) {
        for (ComponentPattern cp : patterns.values()) {
            cp.registerViews(viewDefinitionService);
        }
    }

    public void addComponentPattern(final ComponentPattern componentPattern) {
        patterns.put(componentPattern.getName(), componentPattern);
    }

    @Override
    public ComponentPattern getComponentByPath(final String path) {
        String[] pathParts = path.split("\\.");
        ComponentPattern componentPattern = patterns.get(pathParts[0]);
        if (componentPattern == null) {
            return null;
        }
        for (int i = 1; i < pathParts.length; i++) {
            ContainerPattern container = (ContainerPattern) componentPattern;
            componentPattern = container.getChild(pathParts[i]);
            if (componentPattern == null) {
                return null;
            }
        }
        return componentPattern;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getPluginIdentifier() {
        return pluginIdentifier;
    }

    @Override
    public boolean isMenuAccessible() {
        return menuAccessible;
    }

    @Override
    public DataDefinition getDataDefinition() {
        return dataDefinition;
    };

    public Set<String> getJsFilePaths() {
        return jsFilePaths;
    }

    @Override
    public void addJsFilePath(final String jsFilePath) {
        jsFilePaths.add(jsFilePath);
    }

    public void addPostInitializeHook(final HookDefinition hookDefinition) {
        postInitializeHooks.add(hookDefinition);
    }

    public void addPreRenderHook(final HookDefinition hookDefinition) {
        preRenderHooks.add(hookDefinition);
    }

    public void addPreInitializeHook(final HookDefinition hookDefinition) {
        preInitializeHooks.add(hookDefinition);
    }

    private void callHooks(final List<HookDefinition> hooks, final ViewDefinitionState viewDefinitionState, final Locale locale) {
        for (HookDefinition hook : hooks) {
            hook.callWithViewState(viewDefinitionState, locale);
        }
    }

    private List<ComponentPattern> getPatternsAsList(final Collection<ComponentPattern> patterns) {
        List<ComponentPattern> list = new ArrayList<ComponentPattern>();
        list.addAll(patterns);
        for (ComponentPattern pattern : patterns) {
            if (pattern instanceof ContainerPattern) {
                list.addAll(getPatternsAsList(((ContainerPattern) pattern).getChildren().values()));
            }
        }
        return list;
    }

}
