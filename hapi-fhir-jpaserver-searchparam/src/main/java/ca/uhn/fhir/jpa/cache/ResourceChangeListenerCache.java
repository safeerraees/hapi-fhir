package ca.uhn.fhir.jpa.cache;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryMatchResult;
import ca.uhn.fhir.jpa.searchparam.matcher.SearchParamMatcher;
import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.collections4.SetValuedMap;
import org.apache.commons.collections4.multimap.HashSetValuedHashMap;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ResourceChangeListenerCache {
	private static final Logger ourLog = LoggerFactory.getLogger(ResourceChangeListenerCache.class);

	@Autowired
	FhirContext myFhirContext;
	@Autowired
	SearchParamMatcher mySearchParamMatcher;

	private final SetValuedMap<String, ResourceChangeListenerWithSearchParamMap> myListenersByResourceType = new HashSetValuedHashMap<>();

	public void add(String theResourceType, IResourceChangeListener theResourceChangeListener, SearchParameterMap theMap) {
		getListenerEntries(theResourceType).add(new ResourceChangeListenerWithSearchParamMap(theResourceChangeListener, theMap));
	}

	@VisibleForTesting
	public void clearListenersForUnitTest() {
		myListenersByResourceType.clear();
	}

	public Set<String> resourceNames() {
		return myListenersByResourceType.keySet();
	}

	@Nonnull
	public Set<ResourceChangeListenerWithSearchParamMap> getListenerEntries(String theResourceType) {
		return myListenersByResourceType.get(theResourceType);
	}

	public boolean hasListenerFor(IBaseResource theResource) {
		String resourceName = myFhirContext.getResourceType(theResource);
		return myListenersByResourceType.get(resourceName).stream().anyMatch(entry -> matches(entry.getSearchParameterMap(), theResource));
	}

	private boolean matches(SearchParameterMap theSearchParameterMap, IBaseResource theResource) {
		InMemoryMatchResult result = mySearchParamMatcher.match(theSearchParameterMap, theResource);
		if (!result.isInMemory()) {
			// This should never happen since we detect this at
			ourLog.warn("Search Parameter Map {} cannot be processed in-memory", theSearchParameterMap);
		}
		return result.matched();
	}

	public ResourceChangeResult notifyListener(ResourceChangeListenerWithSearchParamMap theListenerEntry, ResourceVersionCache theOldResourceVersionCache, ResourceVersionMap theNewResourceVersionMap) {
		ResourceChangeResult retval;
		IResourceChangeListener resourceChangeListener = theListenerEntry.getResourceChangeListener();
		if (theListenerEntry.isInitialized()) {
			retval = compareLastVersionMapToNewVersionMapAndNotifyListenerOfChanges(resourceChangeListener, theOldResourceVersionCache, theNewResourceVersionMap);
		} else {
			theOldResourceVersionCache.initialize(theNewResourceVersionMap);
			resourceChangeListener.handleInit(theNewResourceVersionMap.getSourceIds());
			retval = ResourceChangeResult.fromCreated(theNewResourceVersionMap.size());
			theListenerEntry.setInitialized(true);
		}
		return retval;
	}

	public ResourceChangeResult compareLastVersionMapToNewVersionMapAndNotifyListenerOfChanges(IResourceChangeListener theListener, ResourceVersionCache theOldResourceVersionCache, ResourceVersionMap theNewResourceVersionMap) {
		// If the new ResourceVersionMap does not have the old key - delete it
		List<IIdType> deletedIds = new ArrayList<>();
		for (String key : theOldResourceVersionCache.keySet()) {
			Map<IIdType, String> oldVersionCache = theOldResourceVersionCache.getMap(key);
			oldVersionCache.keySet()
				.forEach(id -> {
					if (!theNewResourceVersionMap.containsKey(id)) {
						deletedIds.add(id);
					}
				});
		}
		deletedIds.forEach(theOldResourceVersionCache::remove);

		List<IIdType> createdIds = new ArrayList<>();
		List<IIdType> updatedIds = new ArrayList<>();

		for (IIdType id : theNewResourceVersionMap.keySet()) {
			String previousValue = theOldResourceVersionCache.addOrUpdate(id, theNewResourceVersionMap.get(id));
			IIdType newId = id.withVersion(theNewResourceVersionMap.get(id));
			if (previousValue == null) {
				createdIds.add(newId);
			} else if (!theNewResourceVersionMap.get(id).equals(previousValue)) {
				updatedIds.add(newId);
			}
		}

		ResourceChangeEvent resourceChangeEvent = ResourceChangeEvent.fromCreatedUpdatedDeletedResourceIds(createdIds, updatedIds, deletedIds);
		if (!resourceChangeEvent.isEmpty()) {
			theListener.handleChange(resourceChangeEvent);
		}
		return ResourceChangeResult.fromResourceChangeEvent(resourceChangeEvent);
	}

	public void remove(IResourceChangeListener theResourceChangeListener) {
		myListenersByResourceType.entries().removeIf(entry -> entry.getValue().getResourceChangeListener().equals(theResourceChangeListener));
	}

	public int size() {
		return myListenersByResourceType.size();
	}
}
