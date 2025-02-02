package net.stellarica.core.crafts

import net.stellarica.core.Components.Companion.MULTIBLOCKS
import net.stellarica.core.mixin.BlockEntityMixin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.entity.BlockEntity
import net.minecraft.entity.LivingEntity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.BlockRotation
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.util.math.Vec3i
import net.minecraft.world.chunk.Chunk
import net.minecraft.world.chunk.WorldChunk
import net.stellarica.core.multiblocks.MultiblockInstance
import net.stellarica.core.multiblocks.OriginRelative
import net.stellarica.core.util.asDegrees
import net.stellarica.core.util.rotateCoordinates
import net.stellarica.core.util.sendRichMessage
import org.quiltmc.qkl.library.math.toBlockPos
import org.quiltmc.qkl.library.math.toVec3d
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

open class Craft(var origin: BlockPos, var world: ServerWorld, var owner: ServerPlayerEntity) {
	val sizeLimit = 10000

	var multiblocks = mutableSetOf<WeakReference<MultiblockInstance>>()
	var detectedBlocks = mutableSetOf<BlockPos>()

	var passengers = mutableSetOf<LivingEntity>()

	val blockCount: Int
		get() = detectedBlocks.size

	/**
	 * The blocks considered to be "inside" of the ship, but not neccecarily detected.
	 */
	protected var bounds = mutableSetOf<OriginRelative>()

	/**
	 * @return Whether [block] is considered to be inside this craft
	 */
	fun contains(block: BlockPos?): Boolean {
		block ?: return false
		return detectedBlocks.contains(block) || bounds.contains(block.subtract(origin).let {
			OriginRelative(
				it.x,
				it.y,
				it.z
			)
		})
	}

	fun calculateHitbox() {
		detectedBlocks
			.map { pos ->
				pos.subtract(origin)
					.let { OriginRelative(it.x, it.y, it.z) }
			}
			.sortedBy { -it.y }
			.forEach { block ->
				val max = bounds.filter { it.x == block.x && it.z == block.z }.maxByOrNull { it.y }?.y ?: block.y
				for (y in block.y..max) {
					bounds.add(OriginRelative(block.x, y, block.z))
				}
			}
	}

	fun detect() {
		var nextBlocksToCheck = detectedBlocks
		nextBlocksToCheck.add(origin)
		detectedBlocks = mutableSetOf()
		val checkedBlocks = nextBlocksToCheck.toMutableSet()

		val startTime = System.currentTimeMillis()

		val chunks = mutableSetOf<Chunk>()

		while (nextBlocksToCheck.size > 0) {
			val blocksToCheck = nextBlocksToCheck
			nextBlocksToCheck = mutableSetOf()

			for (currentBlock in blocksToCheck) {

				if (undetectableBlocks.contains(world.getBlockState(currentBlock).block)) continue

				if (detectedBlocks.size > sizeLimit) {
					owner.sendRichMessage("<gold>Detection limit reached. (${sizeLimit} blocks)")
					nextBlocksToCheck.clear()
					detectedBlocks.clear()
					break
				}

				detectedBlocks.add(currentBlock)
				chunks.add(world.getChunk(currentBlock))

				// Slightly condensed from MSP's nonsense, but this could be improved
				for (x in -1..1) {
					for (y in -1..1) {
						for (z in -1..1) {
							if (x == y && z == y && y == 0) continue
							val block = currentBlock.add(x, y, z)
							if (!checkedBlocks.contains(block)) {
								checkedBlocks.add(block)
								nextBlocksToCheck.add(block)
							}
						}
					}
				}
			}
		}

		val elapsed = System.currentTimeMillis() - startTime
		owner.sendRichMessage("<green>Craft detected! (${detectedBlocks.size} blocks)")
		owner.sendRichMessage(
			"<gray>Detected ${detectedBlocks.size} blocks in ${elapsed}ms. " +
					"(${detectedBlocks.size / elapsed.coerceAtLeast(1)} blocks/ms)"
		)
		owner.sendRichMessage(
			"<gray>Calculated Hitbox in ${
				measureTimeMillis {
					calculateHitbox()
				}
			}ms. (${bounds.size} blocks)")

		// Detect all multiblocks
		multiblocks.clear()
		// this is probably slow
		multiblocks.addAll(
			chunks.map { MULTIBLOCKS.get(it).multiblocks }.flatten().filter { detectedBlocks.contains(it.origin) }
				.map { WeakReference(it) })

		owner.sendRichMessage("<gray>Detected ${multiblocks.size} multiblocks")
	}

	fun movePassengers(offset: (Vec3d) -> Vec3d, rotation: BlockRotation) {
		passengers.forEach {
			// TODO: FIX
			// this is not a good solution because if there is any rotation, the player will not be translated by the offset
			// The result is that any ship movement that attempts to rotate and move in the same action will break.
			// For now there aren't any actions like that, but if there are in the future, this will need to be fixed.
			//
			// Rotating the whole ship around the adjusted origin will not work,
			// as rotating the ship 4 times does not bring it back to the original position
			//
			// However, without this dumb fix players do not rotate to the proper relative location
			val destination =
				if (rotation == BlockRotation.CLOCKWISE_90 || rotation == BlockRotation.COUNTERCLOCKWISE_90) rotateCoordinates(
					it.pos,
					Vec3d(
						0.5,
						0.0,
						0.5
					).add(origin.toVec3d()),
					rotation
				)
				else offset(it.pos)
			// todo: handle teleporting to a different world

			if (it is ServerPlayerEntity) {
				it.teleport(
					world,
					destination.x,
					destination.y,
					destination.z,
					it.yaw + rotation.asDegrees.toFloat(),
					it.pitch
				)
			} else {
				it.teleport(destination.x, destination.y, destination.z)
			}
		}
	}

	fun sendMiniMessage(message: String) {
		passengers.forEach {
			if (it is ServerPlayerEntity) {
				it.sendRichMessage(message)
			}
		}
	}

	private fun setBlockFast(pos: BlockPos, state: BlockState, world: ServerWorld) {
		val chunk = world.getChunk(pos) as WorldChunk
		val chunkSection = (pos.y shr 4) - chunk.bottomSectionCoord
		var section = chunk.sectionArray[chunkSection]
		if (section == null) {
			// Put a GLASS block to initialize the section. It will be replaced next with the real block.
			chunk.setBlockState(pos, Blocks.GLASS.defaultState, false)
			section = chunk.sectionArray[chunkSection]
		}
		val oldState = section!!.getBlockState(pos.x and 15, pos.y and 15, pos.z and 15)
		if (oldState == state) return //Block is already of correct type and data, don't overwrite

		section.setBlockState(pos.x and 15, pos.y and 15, pos.z and 15, state)
		world.updateListeners(pos, oldState, state, 3)
		// world.lightEngine.checkBlock(position) // boolean corresponds to if chunk section empty
		//todo: LIGHTING IS FOR CHUMPS!
		chunk.setNeedsSaving(true)
	}

	/**
	 * Translate the craft by [offset] blocks
	 * @see queueChange
	 */
	fun move(offset: Vec3i) {
		val change = offset.toVec3d()
		// don't want to let them pass a vec3d
		// since the ships snap to blocks but entities can actually move by that much
		// relative entity teleportation will be messed up

		change({ current ->
			return@change current.add(change)
		}, world)
	}

	/**
	 * Rotate the craft and contents by [rotation]
	 * @see queueChange
	 */
	fun rotate(rotation: BlockRotation) {
		change({ current ->
			return@change rotateCoordinates(current, origin.toVec3d(), rotation)
		}, world, rotation) {
			calculateHitbox() // rather than keep track of a hitbox rotation, just recacluate it when we rotate.
		}
	}

	private fun change(
		/** The transformation to apply to each block in the craft */
		modifier: (Vec3d) -> Vec3d,
		/** The world to move to */
		targetWorld: ServerWorld,
		/** The amount to rotate the ship by */
		rotation: BlockRotation = BlockRotation.NONE,
		/** Callback called after the craft finishes moving */
		callback: () -> Unit = {}
	) {
		// calculate new block locations
		val targets = ConcurrentHashMap<BlockPos, BlockPos>()
		runBlocking {
			detectedBlocks.chunked(500).forEach { section ->
				// chunk into sections to process parallel
				launch(Dispatchers.Default) {
					section.forEach { current ->
						targets[current] = modifier(current.toVec3d()).toBlockPos()
					}
				}
			}
		}

		// We need to get the original blockstates before we start setting blocks
		// otherwise, if we just try to get the state as we set the block, the state might have already been set.
		// Consider moving a block from b to c. If a has already been moved to b, we don't want to copy a to c.
		// see https://discord.com/channels/1038493335679156425/1038504764356427877/1066184457264046170
		//
		// However, we don't need to go and get the states of the current blocks, as if it isn't in
		// the target blocks, it won't be overwritten, so we can just get it when it comes time to set the block
		//
		// This solution ~~may not be~~ isn't the most efficient, but it works
		val original = mutableMapOf<BlockPos, BlockState>()
		val entities = mutableMapOf<BlockPos, BlockEntity>()

		// check for collisions
		targets.forEach { (_, target) ->
			// todo: it's possible for detectedBlocks to contain it but not actually be detected (if the world is different)
			val state = targetWorld.getBlockState(target)
			if (!state.isAir && !detectedBlocks.contains(target)) {
				sendMiniMessage("<gold>Blocked by ${world.getBlockState(target).block.name} at <bold>(${target.x}, ${target.y}, ${target.z}</bold>)!\"")
				return
			}
			// also use this time to get the original state of these blocks
			if (state.hasBlockEntity()) {
				entities[target] = targetWorld.getBlockEntity(target)!!
			}
			original[target] = state
		}

		// if the world we're moving to isn't the world we're coming from, the whole map of original states we got is useless
		if (world != targetWorld) {
			original.clear()
			entities.clear()
		}

		// iterating over twice isn't great
		val newDetectedBlocks = mutableSetOf<BlockPos>()
		targets.forEach { (current, target) ->
			val currentBlock = original.getOrElse(current) {world.getBlockState(current)}

			// set the block
			setBlockFast(target, currentBlock.rotate(rotation), targetWorld)
			newDetectedBlocks.add(target)

			// move any entities
			if (entities.contains(current) || currentBlock.hasBlockEntity()) {
				val entity = entities.getOrElse(current) {world.getBlockEntity(current)!!}
				(entity as BlockEntityMixin).setPos(target)
				entity.world = targetWorld
				entity.markDirty()
				world.getChunk(target).setBlockEntity(entity)
			}

			// if no other block is moving to where we were, set it to air
			if (current !in targets.values) {
				setBlockFast(current, Blocks.AIR.defaultState, world)
			}
		}

		// move multiblocks
		multiblocks.map {
			val mb = it.get() ?: return@map null
			MULTIBLOCKS.get(world.getChunk(mb.origin)).multiblocks.remove(mb)
			val new = mb.copy(origin = modifier(mb.origin.toVec3d()).toBlockPos())
			MULTIBLOCKS.get(targetWorld.getChunk(new.origin)).multiblocks.add(new)
			return@map new
		}

		// finish up
		movePassengers(modifier, rotation)
		if (newDetectedBlocks.size != detectedBlocks.size) {
			println("Lost ${detectedBlocks.size - newDetectedBlocks.size} blocks while moving! This is a bug!")
		}
		detectedBlocks = newDetectedBlocks
		world = targetWorld
		origin = modifier(origin.toVec3d()).toBlockPos()
		callback()
	}
}