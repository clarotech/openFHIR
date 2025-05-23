package com.medblocks.openfhir.db;

import com.medblocks.openfhir.db.entity.FhirConnectContextEntity;
import com.medblocks.openfhir.db.entity.FhirConnectModelEntity;
import com.medblocks.openfhir.db.repository.FhirConnectContextRepository;
import com.medblocks.openfhir.db.repository.FhirConnectModelRepository;
import com.medblocks.openfhir.fc.schema.context.FhirConnectContext;
import com.medblocks.openfhir.fc.schema.model.FhirConnectModel;
import com.medblocks.openfhir.rest.RequestValidationException;
import com.medblocks.openfhir.util.FhirConnectValidator;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
@Transactional
public class FhirConnectService {

    private final FhirConnectModelRepository modelRepository;
    private final FhirConnectContextRepository contextRepository;
    private final FhirConnectValidator validator;

    @Autowired
    public FhirConnectService(final FhirConnectModelRepository modelRepository,
                              final FhirConnectContextRepository contextRepository,
                              final FhirConnectValidator validator) {
        this.modelRepository = modelRepository;
        this.contextRepository = contextRepository;
        this.validator = validator;
    }

    /**
     * Creates a model mapper based on FHIR Connect specification.
     *
     * @return created Model Mapper populated with database assigned attributes (namely id)
     * @throws RequestValidationException if incoming BODY is not according to model-mapping json schema
     * @throws RequestValidationException if FHIR Paths within the mappers are not valid FHIR Paths
     */
    public FhirConnectModelEntity upsertModelMapper(final FhirConnectModel fhirConnectModel, String id, final String reqId) {
        log.debug("Receive CREATE/UPDATE FhirConnectModel, id {}, reqId: {}", id, reqId);
        try {
            if (StringUtils.isNotEmpty(id)) {
                final FhirConnectModelEntity existingModel = modelRepository.byId(id);
                if (existingModel == null) {
                    id = null; // ensuring it will be created and it won't override someone else model mapper!!
                }
            }

            final List<String> strings = validator.validateAgainstModelSchema(fhirConnectModel);
            if (strings != null && !strings.isEmpty()) {
                log.error(
                        "[{}] Error occurred trying to validate FC model mapper against the schema. Nothing has been created. Errors: {}",
                        reqId, strings);
                throw new RequestValidationException("Couldn't validate against the yaml schema", strings);
            }

            final List<String> semanticErrors = validator.validateFhirConnectModel(fhirConnectModel);
            if (semanticErrors != null && !semanticErrors.isEmpty()) {
                log.error("[{}] Error occurred trying to validate semantic correctness of the mapper.", reqId);
                throw new RequestValidationException(
                        "Error occurred trying to validate semantic correctness of the mapper,", semanticErrors);
            }

            // check if model with this name already exists
            final List<FhirConnectModelEntity> existingModel = modelRepository.findByName(
                    Collections.singletonList(fhirConnectModel.getMetadata().getName()));
            if (id == null && existingModel != null && !existingModel.isEmpty()) {
                throw new RequestValidationException(
                        "Model mapper with this name " + fhirConnectModel.getMetadata().getName()
                                + " already exists. Modify the name of the model mapper or update it (PUT with id) if you want to update an existing one.",
                        null);
            }

            final FhirConnectModelEntity build = FhirConnectModelEntity.builder()
                    .fhirConnectModel(fhirConnectModel)
                    .id(StringUtils.isBlank(id) ? null : id)
                    .build();
            final FhirConnectModelEntity saved = modelRepository.save(build);
            saved.setFhirConnectModel(
                    fhirConnectModel); // unless we do this, when postgres is used, this will be empty in response
            saved.getFhirConnectModel().setId(saved.getId());
            return saved;
        } catch (final RequestValidationException e) {
            throw e;
        } catch (final Exception e) {
            log.error("Couldn't create a FhirConnectModel, reqId: {}", reqId, e);
            throw new IllegalArgumentException("Couldn't create a FhirConnectModel. Invalid one.", e);
        }
    }

    public FhirConnectContextEntity findContextMapperByTemplate(final String normalizedTemplateId) {
        return contextRepository.findByTemplateId(normalizedTemplateId);
    }

    /**
     * Creates a context mapper based on FHIR Connect specification.
     *
     * @return created Context Mapper populated with database assigned attributes (namely id)
     * @throws RequestValidationException if incoming BODY is not according to contextual-mapping json schema
     * @throws IllegalArgumentException if a context mapper fot the given template already exists (there can
     *         only be
     *         one context mapper for a specific template id)
     */
    public FhirConnectContextEntity upsertContextMapper(final FhirConnectContext fhirContext, String id, final String reqId) {

        log.debug("Receive CREATE/UPDATE FhirConnectContext, id {}, reqId: {}", id, reqId);
        try {
            if (StringUtils.isNotEmpty(id)) {
                final FhirConnectContextEntity existingContext = contextRepository.byId(id);
                if (existingContext == null) {
                    id = null; // ensuring it will be created and it won't override someone else context mapper!!
                }
            }

            final List<String> strings = validator.validateAgainstContextSchema(fhirContext);
            if (strings != null && !strings.isEmpty()) {
                log.error(
                        "[{}] Error occurred trying to validate connect context mapper against the schema. Nothing has been created. Errors: {}",
                        reqId, strings);
                throw new RequestValidationException("Couldn't validate against the yaml schema", strings);
            }

            final FhirConnectContextEntity build = FhirConnectContextEntity.builder()
                    .fhirConnectContext(fhirContext)
                    .id(StringUtils.isBlank(id) ? null : id)
                    .build();

            // only if the same one for that template id doesn't already exist!!
            if (StringUtils.isBlank(id)
                    && contextRepository.findByTemplateId(fhirContext.getContext().getTemplate().getId()) != null) {
                log.error("[{}] A context mapper for this templateId {} already exists.", reqId,
                          fhirContext.getContext().getTemplate().getId());
                throw new RequestValidationException("Couldn't create a FhirConnectContext. Invalid one.",
                                                     List.of("A context mapper for this template already exists."));
            }
            final FhirConnectContextEntity saved = contextRepository.save(build);
            saved.setFhirConnectContext(
                    fhirContext); // unless we do this, when postgres is used, this will be empty in response
            saved.getFhirConnectContext().setId(saved.getId());
            return saved;
        } catch (final RequestValidationException e) {
            throw e;
        } catch (final Exception e) {
            log.error("Couldn't create/update a FhirConnectContext, reqId: {}", reqId, e);
            throw new IllegalArgumentException("Couldn't create a FhirConnectContext. Invalid one.", e);
        }
    }

    public List<FhirConnectModel> allModelMappers(final String reqId) {
        final List<FhirConnectModelEntity> byTenant = modelRepository.findAll();
        return byTenant == null ? null : byTenant.stream().map(
                FhirConnectModelEntity::getFhirConnectModel).collect(Collectors.toList());
    }

    public FhirConnectModel readModelMapper(final String id) {
        final FhirConnectModelEntity modelEntity = modelRepository.byId(id);
        return modelEntity == null ? null : modelEntity.getFhirConnectModel();
    }

    public FhirConnectContext readContextMappers(final String id) {
        final FhirConnectContextEntity fhirConnectContextEntity = contextRepository.byId(id);
        return fhirConnectContextEntity == null ? null : fhirConnectContextEntity.getFhirConnectContext();
    }

    public List<FhirConnectContext> allContextMappers(final String reqId) {
        final List<FhirConnectContextEntity> byTenant = contextRepository.findAll();
        return byTenant == null ? null :  byTenant.stream().map(
                FhirConnectContextEntity::getFhirConnectContext).collect(Collectors.toList());
    }

    public List<FhirConnectModel> findByArchetypes(final List<String> archetypes, final String reqId) {
        final List<FhirConnectModelEntity> byTenantAndName = modelRepository.findByName(archetypes);
        return byTenantAndName == null ? null : byTenantAndName.stream().map(
                        FhirConnectModelEntity::getFhirConnectModel)
                .collect(Collectors.toList());
    }
}
