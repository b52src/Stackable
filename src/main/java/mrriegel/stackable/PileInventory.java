package mrriegel.stackable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.Validate;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenCustomHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import mrriegel.stackable.tile.TilePile;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

public class PileInventory implements INBTSerializable<NBTTagCompound>, IItemHandler {

	private final TilePile tile;
	public final Object2IntLinkedOpenCustomHashMap<ItemStack> inventory = new Object2IntLinkedOpenCustomHashMap<>(TilePile.strategyExact);
	public List<ItemStack> items = null;
	boolean threadStarted = false;

	public PileInventory(TilePile tile) {
		this.tile = tile;
	}

	public ItemStack extractItem(ItemStack stack, int amount, boolean simulate) {
		if (stack.isEmpty())
			return ItemStack.EMPTY;
		ItemStack ss = stack;
		int min = tile.min.getInt(stack);
		if (min > 0) {
			int total = inventory.object2IntEntrySet().stream().filter(e -> tile.min.strategy().equals(ss, e.getKey())).mapToInt(e -> e.getIntValue()).sum();
			amount = Math.min(total - min, amount);
		}
		int contain = inventory.getInt(stack);
		int i = Math.min(amount, Math.min(stack.getMaxStackSize(), contain));
		if (tile.persistent && inventory.size() == 1 && i >= contain) {
			i--;
		}
		if (i <= 0)
			return ItemStack.EMPTY;
		if (!simulate) {
			int old = inventory.addTo(stack, -i);
			if (old - i == 0) {
				inventory.removeInt(stack);
				if (inventory.isEmpty()) {
					new Thread(() -> tile.getWorld().getMinecraftServer().addScheduledTask(() -> tile.getWorld().setBlockToAir(tile.getPos()))).start();
				}
			}
			onChange();
		}
		return ItemHandlerHelper.copyStackWithSize(stack, i);
	}

	public ItemStack insertItem(ItemStack stack, boolean simulate) {
		if (!tile.validItem(stack))
			return stack;
		if (tile.useWhitelist && !tile.whitelist.contains(stack))
			return stack;
		if (!tile.useWhitelist && tile.blacklist.contains(stack))
			return stack;
		ItemStack ss = stack;
		int addReturn = 0;
		if (tile.max.containsKey(stack)) {
			int total = inventory.object2IntEntrySet().stream().filter(e -> tile.max.strategy().equals(ss, e.getKey())).mapToInt(e -> e.getIntValue()).sum();
			int max = tile.max.getInt(stack);
			int insert = Math.min(max - total, stack.getCount());
			if (insert <= 0)
				return stack;
			addReturn = stack.getCount() - insert;
			if (insert != stack.getCount())
				stack = ItemHandlerHelper.copyStackWithSize(stack, insert);
		}
		int canInsert = freeItems(stack);
		boolean noSpace = false;
		Set<BlockPos> added = new HashSet<>();
		while (canInsert < stack.getCount() && !noSpace) {
			List<TilePile> l = tile.getAllPileBlocks();
			if (l.size() >= tile.maxPileHeight())
				break;
			TilePile highest = l.get(l.size() - 1);
			BlockPos neu = highest.getPos().up();
			if (tile.getWorld().isAirBlock(neu) && tile.getWorld().setBlockState(neu, tile.getBlockType().getDefaultState(), simulate ? 0 : 3)) {
				TilePile n = (TilePile) tile.getWorld().getTileEntity(neu);
				n.masterPos = tile.getPos();
				n.inv = null;
				added.add(neu);
				canInsert = freeItems(stack);
			} else
				noSpace = true;
		}
		if (!simulate && canInsert > 0) {
			inventory.addTo(stack.copy(), Math.min(stack.getCount(), canInsert));
			onChange();
		}
		if (simulate)
			for (BlockPos p : added)
				tile.getWorld().setBlockState(p, Blocks.AIR.getDefaultState(), 0);
		return canInsert >= stack.getCount() ? ItemHandlerHelper.copyStackWithSize(stack, addReturn) : ItemHandlerHelper.copyStackWithSize(stack, stack.getCount() - canInsert);
	}

	public void cycle(boolean forward) {
		if (inventory.size() < 2)
			return;
		if (!forward) {
			ItemStack s = inventory.firstKey();
			int val = inventory.removeInt(s);
			inventory.putAndMoveToLast(s, val);
		} else {
			ItemStack s = inventory.lastKey();
			int val = inventory.removeInt(s);
			inventory.putAndMoveToFirst(s, val);
		}
		onChange();
	}

	private void onChange() {
		for (TilePile t : tile.getAllPileBlocks()) {
			t.needSync = true;
			t.markDirty();
			t.box = null;
			t.positions = null;
			t.items = null;
			t.raytrace = null;
			if (!t.isMaster && t.itemList().stream().allMatch(ItemStack::isEmpty))
				t.getWorld().setBlockToAir(t.getPos());
		}
		if (!threadStarted) {
			threadStarted = true;
			new Thread(() -> tile.getWorld().getMinecraftServer().addScheduledTask(() -> {
				items = null;
				threadStarted = false;
			})).start();
		}
	}

	private int freeItems(ItemStack stack) {
		int max = Math.min(stack.getMaxStackSize(), tile.itemsPerVisualItem());
		int free = 0;
		int occuItems = 0;
		for (Object2IntMap.Entry<ItemStack> e : inventory.object2IntEntrySet()) {
			if (TilePile.strategyExact.equals(e.getKey(), stack)) {
				int value = e.getIntValue();
				while (value > 0) {
					if (value >= max) {
						occuItems++;
						value -= max;
					} else {
						occuItems++;
						free = max - (value % max);
						break;
					}
				}
			} else {
				occuItems += Math.ceil(e.getIntValue() / (double) (Math.min(e.getKey().getMaxStackSize(), tile.itemsPerVisualItem())));
			}
		}
		int freeItems = tile.maxVisualItems() * tile.getAllPileBlocks().size() - occuItems;
		free += max * freeItems;
		return free;
	}

	@Override
	public NBTTagCompound serializeNBT() {
		NBTTagCompound compound = new NBTTagCompound();
		NBTTagList list1 = new NBTTagList();
		IntArrayList list2 = new IntArrayList();
		for (Object2IntMap.Entry<ItemStack> e : inventory.object2IntEntrySet()) {
			list1.appendTag(e.getKey().writeToNBT(new NBTTagCompound()));
			list2.add(e.getIntValue());
		}
		compound.setTag("list1", list1);
		compound.setIntArray("list2", list2.toIntArray());
		return compound;
	}

	@Override
	public void deserializeNBT(NBTTagCompound compound) {
		NBTTagList list1 = compound.getTagList("list1", 10);
		int[] list2 = compound.getIntArray("list2");
		Validate.isTrue(list1.tagCount() == list2.length);
		inventory.clear();
		for (int i = 0; i < list1.tagCount(); i++) {
			ItemStack s = new ItemStack(list1.getCompoundTagAt(i));
			if (!s.isEmpty())
				inventory.put(s, list2[i]);
		}
	}

	@Override
	public int getSlots() {
		return getItems().size() + 1;
	}

	@Override
	public ItemStack getStackInSlot(int slot) {
		List<ItemStack> l = getItems();
		return slot >= 0 && slot < l.size() ? l.get(slot) : ItemStack.EMPTY;
	}

	@Override
	public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
		return insertItem(stack, simulate);
	}

	@Override
	public ItemStack extractItem(int slot, int amount, boolean simulate) {
		return extractItem(getStackInSlot(slot), amount, simulate);
	}

	@Override
	public int getSlotLimit(int slot) {
		return 64;
	}

	public List<ItemStack> getItems() {
		if (items != null)
			return items;
		items = new ArrayList<>();
		for (Object2IntMap.Entry<ItemStack> e : inventory.object2IntEntrySet()) {
			int value = e.getIntValue();
			int max = e.getKey().getMaxStackSize();
			while (value > 0) {
				int f = Math.min(max, value);
				items.add(ItemHandlerHelper.copyStackWithSize(e.getKey(), f));
				value -= f;
			}
		}
		return items;
	}

}
