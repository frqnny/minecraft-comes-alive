package mca.entity.ai.relationship;

import mca.advancement.criterion.CriterionMCA;
import mca.entity.ai.relationship.family.FamilyTree;
import mca.entity.ai.relationship.family.FamilyTreeNode;
import mca.server.world.data.PlayerSaveData;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public interface EntityRelationship {

    default Gender getGender() {
        return Gender.MALE;
    }

    default FamilyTree getFamilyTree() {
        return FamilyTree.get(getWorld());
    }

    ServerWorld getWorld();

    UUID getUUID();

    @NotNull
    FamilyTreeNode getFamilyEntry();

    default Stream<Entity> getFamily(int parents, int children) {
        return getFamilyEntry()
                .getRelatives(parents, children)
                .map(getWorld()::getEntity)
                .filter(Objects::nonNull)
                .filter(e -> !e.getUuid().equals(getUUID()));
    }

    default Stream<Entity> getParents() {
        return getFamilyEntry().streamParents().map(getWorld()::getEntity).filter(Objects::nonNull);
    }

    default Stream<Entity> getSiblings() {
        return getFamilyEntry()
                .siblings()
                .stream()
                .map(getWorld()::getEntity)
                .filter(Objects::nonNull)
                .filter(e -> !e.getUuid().equals(getUUID())); // we exclude ourselves from the list of siblings
    }

    default Optional<Entity> getPartner() {
        return Optional.ofNullable(getWorld().getEntity(getFamilyEntry().partner()));
    }

    default void onTragedy(DamageSource cause, @Nullable BlockPos burialSite, RelationshipType type, Entity victim) {
        if (type == RelationshipType.STRANGER) {
            return; // effects don't propagate from strangers
        }

        // notify family
        if (type == RelationshipType.SELF) {
            getParents().forEach(parent ->
                    EntityRelationship.of(parent).ifPresent(r ->
                            r.onTragedy(cause, burialSite, RelationshipType.CHILD, victim)
                    )
            );
            getSiblings().forEach(sibling ->
                    EntityRelationship.of(sibling).ifPresent(r ->
                            r.onTragedy(cause, burialSite, RelationshipType.SIBLING, victim)
                    )
            );
            getPartner().flatMap(EntityRelationship::of).ifPresent(r ->
                    r.onTragedy(cause, burialSite, RelationshipType.SPOUSE, victim)
            );
        }

        // end the marriage for both the deceased one and the spouse
        if (type == RelationshipType.SPOUSE || type == RelationshipType.SELF) {
            if (getRelationshipState().isMarried()) {
                endRelationShip(RelationshipState.WIDOW);
            } else {
                endRelationShip(RelationshipState.SINGLE);
            }
        }
    }

    default void marry(Entity spouse) {
        RelationshipState state = spouse instanceof PlayerEntity ? RelationshipState.MARRIED_TO_PLAYER : RelationshipState.MARRIED_TO_VILLAGER;
        if (spouse instanceof ServerPlayerEntity spouseEntity) {
            CriterionMCA.GENERIC_EVENT_CRITERION.trigger(spouseEntity, "marriage");
        }
        getFamilyEntry().updatePartner(spouse, state);
    }

    default void engage(Entity spouse) {
        if (spouse instanceof ServerPlayerEntity spouseEntity) {
            CriterionMCA.GENERIC_EVENT_CRITERION.trigger(spouseEntity, "engage");
        }
        getFamilyEntry().updatePartner(spouse, RelationshipState.ENGAGED);
    }

    default void promise(Entity spouse) {
        if (spouse instanceof ServerPlayerEntity spouseEntity) {
            CriterionMCA.GENERIC_EVENT_CRITERION.trigger(spouseEntity, "promise");
        }
        getFamilyEntry().updatePartner(spouse, RelationshipState.PROMISED);
    }

    default void endRelationShip(RelationshipState newState) {
        getFamilyEntry().updatePartner(null, newState);
    }

    default RelationshipState getRelationshipState() {
        return getFamilyEntry().getRelationshipState();
    }

    default Optional<UUID> getPartnerUUID() {
        UUID spouse = getFamilyEntry().partner();
        if (spouse.equals(Util.NIL_UUID)) {
            return Optional.empty();
        } else {
            return Optional.of(spouse);
        }
    }

    default Optional<Text> getPartnerName() {
        return getFamilyTree().getOrEmpty(getFamilyEntry().partner()).map(FamilyTreeNode::getName).map(Text::literal);
    }

    default boolean isMarried() {
        return getRelationshipState() == RelationshipState.MARRIED_TO_PLAYER || getRelationshipState() == RelationshipState.MARRIED_TO_VILLAGER;
    }

    default boolean isEngaged() {
        return getRelationshipState() == RelationshipState.ENGAGED;
    }

    default boolean isPromised() {
        return getRelationshipState() == RelationshipState.PROMISED;
    }

    default boolean isMarriedTo(UUID uuid) {
        return getPartnerUUID().orElse(Util.NIL_UUID).equals(uuid) && isMarried();
    }

    default boolean isEngagedWith(UUID uuid) {
        return getPartnerUUID().orElse(Util.NIL_UUID).equals(uuid) && isEngaged();
    }

    static Optional<EntityRelationship> of(Entity entity) {
        if (entity instanceof ServerPlayerEntity player) {
            return Optional.ofNullable(PlayerSaveData.get(player));
        }

        if (entity instanceof CompassionateEntity<?> compassionateEntity) {
            return Optional.ofNullable(compassionateEntity.getRelationships());
        }

        return Optional.empty();
    }
}
