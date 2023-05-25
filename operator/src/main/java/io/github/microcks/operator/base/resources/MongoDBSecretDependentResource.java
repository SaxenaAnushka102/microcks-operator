/*
 * Licensed to Laurent Broudoux (the "Author") under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Author licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.github.microcks.operator.base.resources;

import io.github.microcks.operator.MicrocksOperatorConfig;
import io.github.microcks.operator.api.base.v1alpha1.Microcks;
import io.github.microcks.operator.api.base.v1alpha1.MicrocksSpec;
import io.github.microcks.operator.model.NamedSecondaryResourceProvider;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.dependent.Deleter;
import io.javaoperatorsdk.operator.processing.dependent.Creator;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependentResource;
import org.apache.commons.lang3.RandomStringUtils;
import org.jboss.logging.Logger;

/**
 * A MongoDB Kubernetes Secret dependent resource.
 * @author laurent
 */
@KubernetesDependent(labelSelector = MicrocksOperatorConfig.RESOURCE_LABEL_SELECTOR)
public class MongoDBSecretDependentResource extends KubernetesDependentResource<Secret, Microcks>
      implements Creator<Secret, Microcks>, Deleter<Microcks>, NamedSecondaryResourceProvider<Microcks> {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   /** The secret key used to store username. */
   public static final String MONGODB_USER_KEY = "username";
   /** The secret key used to store password. */
   public static final String MONGODB_PASSWORD_KEY = "password";
   /** The secret key used to store admin password. */
   public static final String MONGODB_ADMIN_PASSWORD_KEY = "adminPassword";

   private static final String RESOURCE_SUFFIX = "-mongodb-connection";

   /** Default empty constrcutor. */
   public MongoDBSecretDependentResource() {
      super(Secret.class);
   }

   /**
    * Get the name of Secret given the primary Microcks resource.
    * @param microcks The primary resource
    * @return The name of Srect
    */
   public static final String getSecretName(Microcks microcks) {
      return microcks.getMetadata().getName() + RESOURCE_SUFFIX;
   }

   @Override
   public String getSecondaryResourceName(Microcks primary) {
      return getSecretName(primary);
   }

   @Override
   protected Secret desired(Microcks microcks, Context<Microcks> context) {
      logger.infof("Building desired MongoDB Secret for '%s'", microcks.getMetadata().getName());

      final ObjectMeta microcksMetadata = microcks.getMetadata();
      final String microcksName = microcksMetadata.getName();
      final MicrocksSpec spec = microcks.getSpec();

      SecretBuilder builder = new SecretBuilder()
            .withNewMetadata()
               .withName(getSecondaryResourceName(microcks))
               .withNamespace(microcksMetadata.getNamespace())
               .addToLabels("app", microcksName)
               .addToLabels("container", "mongodb")
               .addToLabels("group", "microcks")
            .endMetadata()
            .withType("kubernetes.io/basic-auth")
            .addToStringData(MONGODB_USER_KEY, "user" + RandomStringUtils.randomAlphanumeric(6))
            .addToStringData(MONGODB_PASSWORD_KEY, RandomStringUtils.randomAlphanumeric(32))
            .addToStringData(MONGODB_ADMIN_PASSWORD_KEY, RandomStringUtils.randomAlphanumeric(32));

      return builder.build();
   }
}
