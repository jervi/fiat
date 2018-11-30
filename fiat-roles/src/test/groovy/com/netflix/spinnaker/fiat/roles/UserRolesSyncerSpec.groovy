/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.fiat.roles

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.appinfo.InstanceInfo.InstanceStatus
import com.netflix.discovery.DiscoveryClient
import com.netflix.spinnaker.fiat.config.ResourceProvidersHealthIndicator
import com.netflix.spinnaker.fiat.config.UnrestrictedResourceConfig
import com.netflix.spinnaker.fiat.model.UserPermission
import com.netflix.spinnaker.fiat.model.resources.Account
import com.netflix.spinnaker.fiat.model.resources.Role
import com.netflix.spinnaker.fiat.model.resources.ServiceAccount
import com.netflix.spinnaker.fiat.permissions.PermissionsResolver
import com.netflix.spinnaker.fiat.permissions.RedisPermissionsRepository
import com.netflix.spinnaker.fiat.providers.ResourceProvider
import com.netflix.spinnaker.kork.jedis.EmbeddedRedis
import com.netflix.spinnaker.kork.jedis.JedisClientDelegate
import com.netflix.spinnaker.kork.lock.LockManager
import org.springframework.boot.actuate.health.Health
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

import java.util.concurrent.Callable

class UserRolesSyncerSpec extends Specification {

  private static final String UNRESTRICTED = UnrestrictedResourceConfig.UNRESTRICTED_USERNAME;

  @Shared
  @AutoCleanup("destroy")
  EmbeddedRedis embeddedRedis

  @Shared
  ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)

  @Shared
  Jedis jedis

  @Shared
  RedisPermissionsRepository repo

  def setupSpec() {
    embeddedRedis = EmbeddedRedis.embed()
    jedis = embeddedRedis.jedis
    jedis.flushDB()
  }

  def setup() {
    repo = new RedisPermissionsRepository(
        objectMapper,
        new JedisClientDelegate(embeddedRedis.pool as JedisPool),
        "unittests"
    )
  }

  def cleanup() {
    jedis.flushDB()
  }

  def "should update user roles & add service accounts"() {
    setup:
    def extRole = new Role("extRole").setSource(Role.Source.EXTERNAL)
    def user1 = new UserPermission()
        .setId("user1")
        .setAccounts([new Account().setName("account1")] as Set)
        .setRoles([extRole] as Set)
    def user2 = new UserPermission()
        .setId("user2")
        .setAccounts([new Account().setName("account2")] as Set)
    def unrestrictedUser = new UserPermission()
        .setId(UnrestrictedResourceConfig.UNRESTRICTED_USERNAME)
        .setAccounts([new Account().setName("unrestrictedAccount")] as Set)

    def abcServiceAcct = new UserPermission().setId("abc")
    def xyzServiceAcct = new UserPermission().setId("xyz@domain.com")

    repo.put(user1)
    repo.put(user2)
    repo.put(unrestrictedUser)

    def newUser2 = new UserPermission()
        .setId("user2")
        .setAccounts([new Account().setName("account3")] as Set)

    def serviceAccountProvider = Mock(ResourceProvider) {
      getAll(false) >> [new ServiceAccount().setName("abc"),
                        new ServiceAccount().setName("xyz@domain.com")]
    }

    def permissionsResolver = Mock(PermissionsResolver)

    def lockManager = Mock(LockManager) {
      _ * acquireLock() >> { LockManager.LockOptions lockOptions, Callable onLockAcquiredCallback ->
        onLockAcquiredCallback.call()
      }
    }

    @Subject
    def syncer = new UserRolesSyncer(
        Optional.ofNullable(null),
        lockManager,
        repo,
        permissionsResolver,
        serviceAccountProvider,
        new AlwaysUpHealthIndicator(),
        1,
        1,
        1,
        1
    )

    expect:
    repo.getAllById() == [
        "user1"       : user1.merge(unrestrictedUser),
        "user2"       : user2.merge(unrestrictedUser),
        (UNRESTRICTED): unrestrictedUser
    ]

    when:
    syncer.syncAndReturn()

    then:
    permissionsResolver.resolve(_ as List) >> ["user1"         : user1,
                                               "user2"         : newUser2,
                                               "abc"           : abcServiceAcct,
                                               "xyz@domain.com": xyzServiceAcct]
    permissionsResolver.resolveUnrestrictedUser() >> unrestrictedUser

    expect:
    repo.getAllById() == [
        "user1"         : user1.merge(unrestrictedUser),
        "user2"         : newUser2.merge(unrestrictedUser),
        "abc"           : abcServiceAcct.merge(unrestrictedUser),
        "xyz@domain.com": xyzServiceAcct.merge(unrestrictedUser),
        (UNRESTRICTED)  : unrestrictedUser
    ]
  }

  @Unroll
  def "should only schedule sync when in-service"() {
    given:
    def lockManager = Mock(LockManager)
    def userRolesSyncer = new UserRolesSyncer(
        Optional.ofNullable(discoveryClient),
        lockManager,
        null,
        null,
        null,
        new AlwaysUpHealthIndicator(),
        1,
        1,
        1,
        1
    )

    when:
    userRolesSyncer.onApplicationEvent(null)
    userRolesSyncer.schedule()

    then:
    (shouldAcquireLock ? 1 : 0) * lockManager.acquireLock(_, _)

    where:
    discoveryClient                                || shouldAcquireLock
    null                                           || true
    discoveryClient(InstanceStatus.UP)             || true
    discoveryClient(InstanceStatus.OUT_OF_SERVICE) || false
    discoveryClient(InstanceStatus.DOWN)           || false
    discoveryClient(InstanceStatus.STARTING)       || false
  }

  DiscoveryClient discoveryClient(InstanceStatus instanceStatus) {
    return Mock(DiscoveryClient) {
      1 * getInstanceRemoteStatus() >> { return instanceStatus }
    }
  }

  class AlwaysUpHealthIndicator extends ResourceProvidersHealthIndicator {
    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
      builder.up()
    }
  }
}
