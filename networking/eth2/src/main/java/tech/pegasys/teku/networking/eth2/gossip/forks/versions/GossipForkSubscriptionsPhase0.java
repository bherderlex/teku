/*
 * Copyright 2021 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.networking.eth2.gossip.forks.versions;

import static tech.pegasys.teku.util.config.Constants.GOSSIP_MAX_SIZE;

import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.networking.eth2.gossip.AggregateGossipManager;
import tech.pegasys.teku.networking.eth2.gossip.AttestationGossipManager;
import tech.pegasys.teku.networking.eth2.gossip.AttesterSlashingGossipManager;
import tech.pegasys.teku.networking.eth2.gossip.BlockGossipManager;
import tech.pegasys.teku.networking.eth2.gossip.GossipManager;
import tech.pegasys.teku.networking.eth2.gossip.ProposerSlashingGossipManager;
import tech.pegasys.teku.networking.eth2.gossip.VoluntaryExitGossipManager;
import tech.pegasys.teku.networking.eth2.gossip.encoding.GossipEncoding;
import tech.pegasys.teku.networking.eth2.gossip.forks.GossipForkSubscriptions;
import tech.pegasys.teku.networking.eth2.gossip.subnets.AttestationSubnetSubscriptions;
import tech.pegasys.teku.networking.eth2.gossip.topics.OperationProcessor;
import tech.pegasys.teku.networking.p2p.discovery.DiscoveryNetwork;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.attestation.ValidateableAttestation;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.operations.AttesterSlashing;
import tech.pegasys.teku.spec.datastructures.operations.ProposerSlashing;
import tech.pegasys.teku.spec.datastructures.operations.SignedVoluntaryExit;
import tech.pegasys.teku.spec.datastructures.operations.versions.altair.SignedContributionAndProof;
import tech.pegasys.teku.spec.datastructures.operations.versions.altair.ValidateableSyncCommitteeMessage;
import tech.pegasys.teku.spec.datastructures.state.Fork;
import tech.pegasys.teku.spec.datastructures.state.ForkInfo;
import tech.pegasys.teku.storage.client.RecentChainData;

public class GossipForkSubscriptionsPhase0 implements GossipForkSubscriptions {

  private final List<GossipManager> gossipManagers = new ArrayList<>();
  private final Fork fork;
  protected final Spec spec;
  protected final AsyncRunner asyncRunner;
  protected final MetricsSystem metricsSystem;
  protected final DiscoveryNetwork<?> discoveryNetwork;
  protected final RecentChainData recentChainData;
  protected final GossipEncoding gossipEncoding;

  // Upstream consumers
  private final OperationProcessor<SignedBeaconBlock> blockProcessor;
  private final OperationProcessor<ValidateableAttestation> attestationProcessor;
  private final OperationProcessor<ValidateableAttestation> aggregateProcessor;
  private final OperationProcessor<AttesterSlashing> attesterSlashingProcessor;
  private final OperationProcessor<ProposerSlashing> proposerSlashingProcessor;
  private final OperationProcessor<SignedVoluntaryExit> voluntaryExitProcessor;

  private AttestationGossipManager attestationGossipManager;
  private AggregateGossipManager aggregateGossipManager;
  private BlockGossipManager blockGossipManager;
  private VoluntaryExitGossipManager voluntaryExitGossipManager;
  private ProposerSlashingGossipManager proposerSlashingGossipManager;
  private AttesterSlashingGossipManager attesterSlashingGossipManager;

  public GossipForkSubscriptionsPhase0(
      final Fork fork,
      final Spec spec,
      final AsyncRunner asyncRunner,
      final MetricsSystem metricsSystem,
      final DiscoveryNetwork<?> discoveryNetwork,
      final RecentChainData recentChainData,
      final GossipEncoding gossipEncoding,
      final OperationProcessor<SignedBeaconBlock> blockProcessor,
      final OperationProcessor<ValidateableAttestation> attestationProcessor,
      final OperationProcessor<ValidateableAttestation> aggregateProcessor,
      final OperationProcessor<AttesterSlashing> attesterSlashingProcessor,
      final OperationProcessor<ProposerSlashing> proposerSlashingProcessor,
      final OperationProcessor<SignedVoluntaryExit> voluntaryExitProcessor) {
    this.fork = fork;
    this.spec = spec;
    this.asyncRunner = asyncRunner;
    this.metricsSystem = metricsSystem;
    this.discoveryNetwork = discoveryNetwork;
    this.recentChainData = recentChainData;
    this.gossipEncoding = gossipEncoding;
    this.blockProcessor = blockProcessor;
    this.attestationProcessor = attestationProcessor;
    this.aggregateProcessor = aggregateProcessor;
    this.attesterSlashingProcessor = attesterSlashingProcessor;
    this.proposerSlashingProcessor = proposerSlashingProcessor;
    this.voluntaryExitProcessor = voluntaryExitProcessor;
  }

  @Override
  public UInt64 getActivationEpoch() {
    return fork.getEpoch();
  }

  @Override
  public final void startGossip(
      final Bytes32 genesisValidatorsRoot, final boolean isOptimisticHead) {
    if (gossipManagers.isEmpty()) {
      final ForkInfo forkInfo = new ForkInfo(fork, genesisValidatorsRoot);
      addGossipManagers(forkInfo);
    }
    gossipManagers.stream()
        .filter(manager -> manager.isEnabledDuringOptimisticSync() || !isOptimisticHead)
        .forEach(GossipManager::subscribe);
  }

  protected void addGossipManagers(final ForkInfo forkInfo) {
    AttestationSubnetSubscriptions attestationSubnetSubscriptions =
        new AttestationSubnetSubscriptions(
            spec,
            asyncRunner,
            discoveryNetwork,
            gossipEncoding,
            recentChainData,
            attestationProcessor,
            forkInfo,
            getMessageMaxSize());

    blockGossipManager =
        new BlockGossipManager(
            recentChainData,
            spec,
            asyncRunner,
            discoveryNetwork,
            gossipEncoding,
            forkInfo,
            blockProcessor,
            getMessageMaxSize());
    addGossipManager(blockGossipManager);

    attestationGossipManager =
        new AttestationGossipManager(metricsSystem, attestationSubnetSubscriptions);
    addGossipManager(attestationGossipManager);

    aggregateGossipManager =
        new AggregateGossipManager(
            recentChainData,
            asyncRunner,
            discoveryNetwork,
            gossipEncoding,
            forkInfo,
            aggregateProcessor,
            getMessageMaxSize());
    addGossipManager(aggregateGossipManager);

    voluntaryExitGossipManager =
        new VoluntaryExitGossipManager(
            recentChainData,
            asyncRunner,
            discoveryNetwork,
            gossipEncoding,
            forkInfo,
            voluntaryExitProcessor,
            getMessageMaxSize());
    addGossipManager(voluntaryExitGossipManager);

    proposerSlashingGossipManager =
        new ProposerSlashingGossipManager(
            recentChainData,
            asyncRunner,
            discoveryNetwork,
            gossipEncoding,
            forkInfo,
            proposerSlashingProcessor,
            getMessageMaxSize());
    addGossipManager(proposerSlashingGossipManager);

    attesterSlashingGossipManager =
        new AttesterSlashingGossipManager(
            recentChainData,
            asyncRunner,
            discoveryNetwork,
            gossipEncoding,
            forkInfo,
            attesterSlashingProcessor,
            getMessageMaxSize());
    addGossipManager(attesterSlashingGossipManager);
  }

  protected void addGossipManager(final GossipManager gossipManager) {
    gossipManagers.add(gossipManager);
  }

  @Override
  public void stopGossip() {
    gossipManagers.forEach(GossipManager::unsubscribe);
  }

  @Override
  public void stopGossipForOptimisticSync() {
    gossipManagers.stream()
        .filter(manager -> !manager.isEnabledDuringOptimisticSync())
        .forEach(GossipManager::unsubscribe);
  }

  @Override
  public void publishAttestation(final ValidateableAttestation attestation) {
    attestationGossipManager.onNewAttestation(attestation);
    aggregateGossipManager.onNewAggregate(attestation);
  }

  @Override
  public void publishBlock(final SignedBeaconBlock block) {
    blockGossipManager.publishBlock(block);
  }

  @Override
  public void publishSyncCommitteeMessage(final ValidateableSyncCommitteeMessage message) {
    // Does not apply to this fork.
  }

  @Override
  public void publishSyncCommitteeContribution(final SignedContributionAndProof message) {
    // Does not apply to this fork.
  }

  @Override
  public void publishProposerSlashing(final ProposerSlashing message) {
    proposerSlashingGossipManager.publishProposerSlashing(message);
  }

  @Override
  public void publishAttesterSlashing(final AttesterSlashing message) {
    attesterSlashingGossipManager.publishAttesterSlashing(message);
  }

  @Override
  public void publishVoluntaryExit(final SignedVoluntaryExit message) {
    voluntaryExitGossipManager.publishVoluntaryExit(message);
  }

  @Override
  public void subscribeToAttestationSubnetId(final int subnetId) {
    attestationGossipManager.subscribeToSubnetId(subnetId);
  }

  @Override
  public void unsubscribeFromAttestationSubnetId(final int subnetId) {
    attestationGossipManager.unsubscribeFromSubnetId(subnetId);
  }

  @Override
  public void subscribeToSyncCommitteeSubnet(final int subnetId) {
    // Does not apply to this fork.
  }

  @Override
  public void unsubscribeFromSyncCommitteeSubnet(final int subnetId) {
    // Does not apply to this fork.
  }

  protected int getMessageMaxSize() {
    return GOSSIP_MAX_SIZE;
  }
}
