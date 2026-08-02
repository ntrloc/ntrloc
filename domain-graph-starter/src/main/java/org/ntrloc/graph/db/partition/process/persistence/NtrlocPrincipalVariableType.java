package org.ntrloc.graph.db.partition.process.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.variable.api.types.ValueFields;
import org.flowable.variable.api.types.VariableType;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.ResolvedPrincipal;

// Lets a whole NtrlocPrincipal round-trip as a single process variable (registered as a
// customPreVariableType in ProcessEngineConfig, typeName "ntrlocPrincipal") -- serialized as JSON,
// into the same text_value TEXT column VariableInstanceDataManagerImpl already persists for every
// other variable type, no schema change needed. This is what lets a script task read
// execution.getVariable("principal") and get a real object straight back, rather than being handed
// just an externalId and having to re-resolve it against SecurityRepository on every single call
// site (see ProcessAdminController.PRINCIPAL_VARIABLE / PrincipalResolver's own history).
// A dedicated, self-contained ObjectMapper (not the app-wide Jackson bean) -- this type is
// constructed directly in ProcessEngineConfig alongside the other hand-built Flowable
// customizations (DataManagers, etc.), not through Spring DI, and its JSON shape is narrow and
// fixed enough not to need whatever the app-wide mapper is configured for elsewhere.
// FAIL_ON_UNKNOWN_PROPERTIES is off defensively (schema evolution tolerance for a value that
// lives in the database, not just in flight), even though NtrlocPrincipal.getName() is already
// @JsonIgnore'd to keep today's shape exactly matching ResolvedPrincipal's record components.
public class NtrlocPrincipalVariableType implements VariableType {

    public static final String TYPE_NAME = "ntrlocPrincipal";

    // The process variable name a resolved NtrlocPrincipal is stored under -- set by
    // ProcessAdminController.startProcessInstance for an HTTP-triggered run, or by
    // ProcessRunAsUserListener for a process with no HTTP caller (a timer, e.g.) that declares
    // flowable:runAsUser. A single shared constant since both are now real callers, not just one.
    public static final String PRINCIPAL_VARIABLE = "principal";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getTypeName() {
        return TYPE_NAME;
    }

    @Override
    public boolean isCachable() {
        return true;
    }

    @Override
    public boolean isAbleToStore(Object value) {
        return value == null || value instanceof NtrlocPrincipal;
    }

    @Override
    public void setValue(Object value, ValueFields valueFields) {
        if (value == null) {
            valueFields.setTextValue(null);
            return;
        }
        // Always re-wrapped into a bare ResolvedPrincipal before serializing -- never the value
        // object as handed in verbatim. Confirmed live: PrincipalResolver's fast path can hand
        // this an NtrlocUserDetails (a real Authentication principal for local-credential
        // sessions), which also implements Spring Security's UserDetails -- plain Jackson
        // reflection on that concrete class serializes getPassword() (the bcrypt hash),
        // getAuthorities(), isEnabled(), etc. right alongside the intended fields, since Jackson
        // has no idea the declared type here is only the five-field NtrlocPrincipal interface.
        // Rebuilding a ResolvedPrincipal from the interface accessors is what actually enforces
        // that shape, regardless of which NtrlocPrincipal implementation is passed in today or
        // added later.
        NtrlocPrincipal principal = (NtrlocPrincipal) value;
        ResolvedPrincipal safe = new ResolvedPrincipal(
                principal.id(), principal.externalId(), principal.displayName(), principal.email(),
                principal.groupIds(), principal.isSuperuser());
        try {
            valueFields.setTextValue(MAPPER.writeValueAsString(safe));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize NtrlocPrincipal variable", e);
        }
    }

    @Override
    public Object getValue(ValueFields valueFields) {
        String textValue = valueFields.getTextValue();
        if (textValue == null) {
            return null;
        }
        try {
            return MAPPER.readValue(textValue, ResolvedPrincipal.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize NtrlocPrincipal variable", e);
        }
    }
}
