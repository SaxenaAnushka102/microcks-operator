/*
 * Copyright The Microcks Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.microcks.operator.base.resources;

import io.github.microcks.operator.MicrocksOperatorConfig;
import io.github.microcks.operator.api.base.v1alpha1.KafkaAuthenticationType;
import io.github.microcks.operator.api.base.v1alpha1.KafkaSpec;
import io.github.microcks.operator.api.base.v1alpha1.Microcks;
import io.github.microcks.operator.model.NamedSecondaryResourceProvider;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.javaoperatorsdk.operator.ReconcilerUtils;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;
import org.jboss.logging.Logger;

import java.util.Objects;

/**
 * A Microcks Kubernetes Deployment dependent resource.
 * @author laurent
 */
@KubernetesDependent(labelSelector = MicrocksOperatorConfig.RESOURCE_LABEL_SELECTOR)
public class MicrocksDeploymentDependentResource extends CRUDKubernetesDependentResource<Deployment, Microcks>
      implements NamedSecondaryResourceProvider<Microcks> {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   /** Default empty constructor. */
   public MicrocksDeploymentDependentResource() {
      super(Deployment.class);
   }

   /**
    * Get the name of Deployment given the primary Microcks resource.
    * @param microcks The primary resource
    * @return The name of Deployment
    */
   public static final String getDeploymentName(Microcks microcks) {
      return microcks.getMetadata().getName();
   }

   @Override
   public String getSecondaryResourceName(Microcks primary) {
      return getDeploymentName(primary);
   }

   @Override
   protected Deployment desired(Microcks microcks, Context<Microcks> context) {
      logger.infof("Building desired Microcks Deployment for '%s'", microcks.getMetadata().getName());

      final ObjectMeta microcksMetadata = microcks.getMetadata();
      final String microcksName = microcksMetadata.getName();

      Deployment deployment = ReconcilerUtils.loadYaml(Deployment.class, getClass(), "/k8s/microcks-deployment.yml");
      DeploymentBuilder builder = new DeploymentBuilder(deployment);
      builder.editMetadata().withName(getDeploymentName(microcks)).withNamespace(microcksMetadata.getNamespace())
            .addToLabels("app", microcksName).addToLabels("app.kubernetes.io/name", getDeploymentName(microcks))
            .addToLabels("app.kubernetes.io/version", microcks.getSpec().getVersion())
            .addToLabels("app.kubernetes.io/part-of", microcksName)
            .addToAnnotations("app.openshift.io/connects-to",
                  MongoDBDeploymentDependentResource.getDeploymentName(microcks) + ","
                        + PostmanRuntimeDeploymentDependentResource.getDeploymentName(microcks) + ","
                        + KeycloakDeploymentDependentResource.getDeploymentName(microcks))
            .endMetadata().editSpec().editSelector().addToMatchLabels("app", microcksName).endSelector().editTemplate()
            // make sure label selector matches label (which has to be matched by service selector too)
            .editMetadata().addToLabels("app", microcksName).endMetadata().editSpec().editFirstContainer()
            .withImage("quay.io/microcks/microcks:nightly").addNewEnv().withName("SPRING_PROFILES_ACTIVE")
            .withValue("prod").endEnv().addNewEnv().withName("SPRING_DATA_MONGODB_URI")
            .withValue(getMongoDBConnection(microcks)).endEnv().addNewEnv().withName("SPRING_DATA_MONGODB_DATABASE")
            .withValue(getMongoDBDatabase(microcks)).endEnv().addNewEnv().withName("SPRING_DATA_MONGODB_USER")
            .withNewValueFrom().withNewSecretKeyRef().withName(getMongoDBSecretName(microcks))
            .withKey(getMongoDBSecretUsernameKey(microcks)).endSecretKeyRef().endValueFrom().endEnv().addNewEnv()
            .withName("SPRING_DATA_MONGODB_PASSWORD").withNewValueFrom().withNewSecretKeyRef()
            .withName(getMongoDBSecretName(microcks)).withKey(getMongoDBSecretPasswordKey(microcks)).endSecretKeyRef()
            .endValueFrom().endEnv().addNewEnv().withName("KEYCLOAK_URL").withValue(getKeycloakUrl(microcks)).endEnv()
            .addNewEnv().withName("KAFKA_BOOTSTRAP_SERVER").withValue(getKafkaUrl(microcks)).endEnv().endContainer()
            .endSpec().endTemplate().endSpec();

      if (microcks.getSpec().getKeycloak().getPrivateUrl() != null) {
         builder.editSpec().editTemplate().editSpec().editFirstContainer().addNewEnv().withName("KEYCLOAK_PUBLIC_URL")
               .withValue(getKeycloakPublicUrl(microcks)).endEnv().endContainer().endSpec().endTemplate().endSpec();
      }

      if (microcks.getSpec().getFeatures().getAsync().isEnabled()
            && !microcks.getSpec().getFeatures().getAsync().getKafka().isInstall()) {

         KafkaSpec kafkaSpec = microcks.getSpec().getFeatures().getAsync().getKafka();
         if (!KafkaAuthenticationType.NONE.equals(kafkaSpec.getAuthentication().getType())) {
            builder.editSpec().editTemplate().editSpec().editFirstContainer().addNewEnv()
                  .withName("KAFKA_TRUSTSTORE_PASSWORD").withNewValueFrom().withNewSecretKeyRef()
                  .withName(kafkaSpec.getAuthentication().getTruststoreSecretRef().getName())
                  .withKey(kafkaSpec.getAuthentication().getTruststoreSecretRef().getAdditionalProperties()
                        .get("passwordKey").toString())
                  .endSecretKeyRef().endValueFrom().endEnv().endContainer().endSpec().endTemplate().endSpec();
         }
         if (KafkaAuthenticationType.SSL.equals(kafkaSpec.getAuthentication().getType())) {
            builder.editSpec().editTemplate().editSpec().editFirstContainer().addNewEnv()
                  .withName("KAFKA_KEYSTORE_PASSWORD").withNewValueFrom().withNewSecretKeyRef()
                  .withName(kafkaSpec.getAuthentication().getKeystoreSecretRef().getName())
                  .withKey(kafkaSpec.getAuthentication().getKeystoreSecretRef().getAdditionalProperties()
                        .get("passwordKey").toString())
                  .endSecretKeyRef().endValueFrom().endEnv().endContainer().endSpec().endTemplate().endSpec();
         }
      }

      return builder.build();
   }

   private String getMongoDBConnection(Microcks microcks) {
      StringBuilder result = new StringBuilder(
            "mongodb://${SPRING_DATA_MONGODB_USER}:${SPRING_DATA_MONGODB_PASSWORD}@");

      if (microcks.getSpec().getMongoDB().getUri() != null) {
         result.append(microcks.getSpec().getMongoDB().getUri());
      } else {
         result.append(MongoDBServiceDependentResource.getServiceName(microcks));
         result.append(":").append(MongoDBServiceDependentResource.MONGODB_SERVICE_PORT);
      }

      result.append("/${SPRING_DATA_MONGODB_DATABASE}");
      result.append(Objects.requireNonNullElse(microcks.getSpec().getMongoDB().getUriParameters(), ""));
      return result.toString();
   }

   public String getMongoDBDatabase(Microcks microcks) {
      return Objects.requireNonNullElse(microcks.getSpec().getMongoDB().getDatabase(),
            microcks.getMetadata().getName());
   }

   private String getMongoDBSecretName(Microcks microcks) {
      if (microcks.getSpec().getMongoDB().getSecretRef() != null) {
         return microcks.getSpec().getMongoDB().getSecretRef().getName();
      }
      return MongoDBSecretDependentResource.getSecretName(microcks);
   }

   private String getMongoDBSecretUsernameKey(Microcks microcks) {
      if (microcks.getSpec().getMongoDB().getSecretRef() != null) {
         return Objects.requireNonNullElse(
               microcks.getSpec().getMongoDB().getSecretRef().getAdditionalProperties().get("usernameKey"),
               MongoDBSecretDependentResource.MONGODB_USER_KEY).toString();
      }
      return MongoDBSecretDependentResource.MONGODB_USER_KEY;
   }

   private String getMongoDBSecretPasswordKey(Microcks microcks) {
      if (microcks.getSpec().getMongoDB().getSecretRef() != null) {
         return Objects.requireNonNullElse(
               microcks.getSpec().getMongoDB().getSecretRef().getAdditionalProperties().get("passwordKey"),
               MongoDBSecretDependentResource.MONGODB_PASSWORD_KEY).toString();
      }
      return MongoDBSecretDependentResource.MONGODB_PASSWORD_KEY;
   }

   private String getKeycloakUrl(Microcks microcks) {
      if (microcks.getSpec().getKeycloak().getPrivateUrl() != null) {
         return microcks.getSpec().getKeycloak().getPrivateUrl();
      }
      return getKeycloakPublicUrl(microcks);
   }

   private String getKeycloakPublicUrl(Microcks microcks) {
      if (microcks.getSpec().getKeycloak().getUrl() != null) {
         return "https://" + microcks.getSpec().getKeycloak().getUrl();
      }
      return "https://" + microcks.getStatus().getKeycloakUrl();
   }

   private String getKafkaUrl(Microcks microcks) {
      if (microcks.getSpec().getFeatures().getAsync().isEnabled()) {
         if (microcks.getSpec().getFeatures().getAsync().getKafka().isInstall()) {
            return microcks.getMetadata().getName() + "-kafka-kafka-bootstrap:9092";
         }
         return microcks.getSpec().getFeatures().getAsync().getKafka().getUrl();
      }
      return "";
   }
}
