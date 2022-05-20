package net.novauniverse.games.tnttag.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Difficulty;
import org.bukkit.Effect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitRunnable;

import net.novauniverse.games.tnttag.NovaTNTTag;
import net.novauniverse.games.tnttag.game.event.PlayerKilledPlayerInTNTTagEvent;
import net.novauniverse.games.tnttag.game.mapmodule.config.TNTTagConfigMapModule;
import net.zeeraa.novacore.commons.log.Log;
import net.zeeraa.novacore.commons.tasks.Task;
import net.zeeraa.novacore.commons.utils.TextUtils;
import net.zeeraa.novacore.spigot.NovaCore;
import net.zeeraa.novacore.spigot.abstraction.VersionIndependantUtils;
import net.zeeraa.novacore.spigot.abstraction.enums.VersionIndependantSound;
import net.zeeraa.novacore.spigot.gameengine.module.modules.game.GameEndReason;
import net.zeeraa.novacore.spigot.gameengine.module.modules.game.MapGame;
import net.zeeraa.novacore.spigot.gameengine.module.modules.game.elimination.PlayerEliminationReason;
import net.zeeraa.novacore.spigot.gameengine.module.modules.game.elimination.PlayerQuitEliminationAction;
import net.zeeraa.novacore.spigot.tasks.SimpleTask;
import net.zeeraa.novacore.spigot.utils.ItemBuilder;
import net.zeeraa.novacore.spigot.utils.PlayerUtils;
import net.zeeraa.novacore.spigot.utils.RandomFireworkEffect;

public class TNTTag extends MapGame implements Listener {
	public static final double PLAYER_HEAD_TNT_Y_OFFSET = 2.5;

	private boolean started;
	private boolean ended;

	private List<UUID> taggedPlayers;

	private Map<UUID, UUID> taggedBy;

	private boolean roundActive;
	private int roundTimer;

	private TNTTagConfigMapModule config;

	private Task timerTask;
	private Task messageTask;

	public TNTTag() {
		super(NovaTNTTag.getInstance());

		this.started = false;
		this.ended = false;
		this.taggedPlayers = new ArrayList<UUID>();
		this.taggedBy = new HashMap<UUID, UUID>();

		this.roundActive = false;
		this.roundTimer = 0;

		this.config = null;

		this.timerTask = new SimpleTask(getPlugin(), new Runnable() {
			@Override
			public void run() {
				if (roundActive) {
					if (roundTimer > 1) {
						roundTimer--;
						if (roundTimer == 10) {
							Bukkit.getServer().getOnlinePlayers().forEach(player -> VersionIndependantUtils.get().sendTitle(player, "", ChatColor.YELLOW + TextUtils.ICON_WARNING + " 10 seconds remaining " + TextUtils.ICON_WARNING, 10, 40, 10));
						}
					} else {
						roundTimer = 0;
						endRound();
					}
				}
			}
		}, 20L);

		this.messageTask = new SimpleTask(getPlugin(), new Runnable() {
			@Override
			public void run() {
				Bukkit.getServer().getOnlinePlayers().forEach(player -> {
					if (players.contains(player.getUniqueId())) {
						if (taggedPlayers.contains(player.getUniqueId())) {
							VersionIndependantUtils.get().sendActionBarMessage(player, ChatColor.RED + TextUtils.ICON_WARNING + " Tagged " + TextUtils.ICON_WARNING);
						} else {
							VersionIndependantUtils.get().sendActionBarMessage(player, ChatColor.GREEN + "Safe");
						}
					}
				});
			}
		}, 10L);
	}

	public boolean isRoundActive() {
		return roundActive;
	}

	public int getRoundTimer() {
		return roundTimer;
	}

	@Override
	public String getName() {
		return "tnttag";
	}

	@Override
	public String getDisplayName() {
		return "TNT Tag";
	}

	@Override
	public PlayerQuitEliminationAction getPlayerQuitEliminationAction() {
		return NovaTNTTag.getInstance().isAllowReconnect() ? PlayerQuitEliminationAction.DELAYED : PlayerQuitEliminationAction.INSTANT;
	}

	@Override
	public boolean eliminatePlayerOnDeath(Player player) {
		return true;
	}

	@Override
	public boolean isPVPEnabled() {
		return true;
	}

	@Override
	public boolean autoEndGame() {
		return true;
	}

	@Override
	public boolean hasStarted() {
		return started;
	}

	@Override
	public boolean hasEnded() {
		return ended;
	}

	@Override
	public boolean isFriendlyFireAllowed() {
		return false;
	}

	@Override
	public boolean canAttack(LivingEntity attacker, LivingEntity target) {
		return true;
	}

	public TNTTagConfigMapModule getConfig() {
		return config;
	}

	public List<UUID> getTaggedPlayers() {
		return taggedPlayers;
	}

	public void endRound() {
		if (!roundActive) {
			return;
		}

		roundActive = false;

		taggedPlayers.forEach(uuid -> {
			OfflinePlayer offlinePlayer = Bukkit.getServer().getOfflinePlayer(uuid);
			this.eliminatePlayer(offlinePlayer, null, PlayerEliminationReason.DEATH);

			Player player = Bukkit.getServer().getPlayer(uuid);
			if (player != null) {
				player.setGameMode(GameMode.SPECTATOR);

				VersionIndependantSound.EXPLODE.playAtLocation(player.getLocation());
				player.getWorld().playEffect(player.getLocation(), Effect.EXPLOSION_HUGE, 10, 1);
			}
		});
		int eliminated = taggedPlayers.size();
		taggedPlayers.clear();

		// Call events
		taggedBy.forEach((player, tagger) -> {
			OfflinePlayer playerPlayer = Bukkit.getOfflinePlayer(tagger);
			OfflinePlayer taggerPlayer = Bukkit.getOfflinePlayer(tagger);

			PlayerKilledPlayerInTNTTagEvent event = new PlayerKilledPlayerInTNTTagEvent(taggerPlayer, playerPlayer);
			Bukkit.getServer().getPluginManager().callEvent(event);
		});
		taggedBy.clear();

		Bukkit.getServer().broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + eliminated + " player" + (eliminated == 1 ? "" : "s") + " eliminated this round");

		new BukkitRunnable() {
			@Override
			public void run() {
				if (!started || ended) {
					return;
				}
				startRoundWait();
			}
		}.runTaskLater(getPlugin(), 20L);
	}

	public void startRound() {
		if (roundActive) {
			return;
		}

		Log.debug("TNTTag", "Starting round");

		List<Player> onlinePlayers = new ArrayList<>();
		Bukkit.getServer().getOnlinePlayers().forEach(player -> {
			if (players.contains(player.getUniqueId())) {
				onlinePlayers.add(player);
			}
		});

		int toTag = 0;
		if (onlinePlayers.size() >= 2) {
			toTag = (int) Math.ceil(onlinePlayers.size() / getConfig().getToTagDivision());
			if (toTag == 0) {
				toTag = 1;
			}
		}

		Log.debug("TNTTag", "To tag count: " + toTag);

		Collections.shuffle(onlinePlayers, getRandom());

		for (int i = 0; i < toTag; i++) {
			Player player = onlinePlayers.remove(0);
			tagPlayer(player, null);
		}

		Bukkit.getServer().broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + toTag + " player" + (toTag == 1 ? "" : "s") + " tagged");

		roundTimer = getConfig().getRoundTime();
		roundActive = true;
	}

	public void startRoundWait() {
		int time = getConfig().getRoundWaitingTime();
		Log.debug("TNTTag", "Waiting for " + time + " seconds before starting next round");
		Bukkit.getServer().broadcastMessage(ChatColor.GOLD + "Next round starting in " + time + " seconds");
		new BukkitRunnable() {
			@Override
			public void run() {
				startRound();
			}
		}.runTaskLater(getPlugin(), time * 20);
	}

	public void untagPlayer(Player player) {
		if (!taggedPlayers.contains(player.getUniqueId())) {
			return;
		}

		player.getInventory().setHelmet(ItemBuilder.AIR);

		taggedBy.remove(player.getUniqueId());

		taggedPlayers.remove(player.getUniqueId());
		player.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "You are no longer tagged");
		VersionIndependantUtils.get().sendTitle(player, "", ChatColor.GREEN + "No longer tagged", 0, 10, 5);
		VersionIndependantSound.ORB_PICKUP.play(player, 0.5F, 1.5F);
	}

	public void tagPlayer(Player player, @Nullable Player attacker) {
		if (taggedPlayers.contains(player.getUniqueId())) {
			return;
		}

		if (attacker != null) {
			taggedBy.put(player.getUniqueId(), attacker.getUniqueId());
		}

		ItemBuilder tntBuilder = ItemBuilder.getPlayerSkullWithBase64TextureAsBuilder(TNTTagTextures.TNT);
		tntBuilder.setName(ChatColor.RED + "TNT");
		tntBuilder.addLore(ChatColor.RED + "RUUUUUUUUUN!!!!!!!!");
		player.getInventory().setHelmet(tntBuilder.build());

		taggedPlayers.add(player.getUniqueId());
		player.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "You have been tagged");
		VersionIndependantUtils.get().sendTitle(player, ChatColor.RED + TextUtils.ICON_WARNING + " Tagged " + TextUtils.ICON_WARNING, "", 0, 10, 5);
		VersionIndependantSound.ITEM_BREAK.play(player, 0.5F, 1.2F);
	}

	public void tpToSpectator(Player player) {
		NovaCore.getInstance().getVersionIndependentUtils().resetEntityMaxHealth(player);
		player.setHealth(20);
		player.setGameMode(GameMode.SPECTATOR);
		if (hasActiveMap()) {
			player.teleport(getActiveMap().getSpectatorLocation());
		}
	}

	/**
	 * Teleport a player to a provided start location
	 * 
	 * @param player   {@link Player} to teleport
	 * @param location {@link Location} to teleport the player to
	 */
	protected void tpToArena(Player player, Location location) {
		player.teleport(location.getWorld().getSpawnLocation());
		PlayerUtils.clearPlayerInventory(player);
		PlayerUtils.clearPotionEffects(player);
		PlayerUtils.resetPlayerXP(player);
		player.setHealth(player.getMaxHealth());
		player.setSaturation(20);
		player.setFoodLevel(20);
		player.setGameMode(GameMode.SURVIVAL);
		player.teleport(location);

		new BukkitRunnable() {
			@Override
			public void run() {
				player.teleport(location);
			}
		}.runTaskLater(NovaTNTTag.getInstance(), 10L);
	}

	@Override
	public void onStart() {
		if (started) {
			return;
		}

		TNTTagConfigMapModule cfg = (TNTTagConfigMapModule) this.getActiveMap().getMapData().getMapModule(TNTTagConfigMapModule.class);
		if (cfg == null) {
			Log.fatal("TNTTag", "The map " + this.getActiveMap().getMapData().getMapName() + " has no tnttag config map module");
			Bukkit.getServer().broadcastMessage(ChatColor.RED + "TNTRun has run into an uncorrectable error and has to be ended");
			this.endGame(GameEndReason.ERROR);
			return;
		}
		this.config = cfg;

		world.setDifficulty(Difficulty.PEACEFUL);

		List<Player> toTeleport = new ArrayList<Player>();

		Bukkit.getServer().getOnlinePlayers().forEach(player -> {
			if (players.contains(player.getUniqueId())) {
				toTeleport.add(player);
			} else {
				tpToSpectator(player);
			}
		});

		Collections.shuffle(toTeleport);

		List<Location> toUse = new ArrayList<Location>();
		while (toTeleport.size() > 0) {
			if (toUse.size() == 0) {
				for (Location location : getActiveMap().getStarterLocations()) {
					toUse.add(location);
				}

				Collections.shuffle(toUse);
			}

			if (toUse.size() == 0) {
				// Could not load spawn locations. break out to prevent server from crashing
				Log.fatal("TNTRun", "The map " + this.getActiveMap().getMapData().getMapName() + " has no spawn locations. Ending game to prevent crash");
				Bukkit.getServer().broadcastMessage(ChatColor.RED + "TNTRun has run into an uncorrectable error and has to be ended");
				this.endGame(GameEndReason.ERROR);
				return;
			}

			tpToArena(toTeleport.remove(0), toUse.remove(0));
		}

		// Disable drops
		this.getActiveMap().getWorld().setGameRuleValue("doTileDrops", "false");

		Task.tryStartTask(timerTask);
		Task.tryStartTask(messageTask);

		started = true;
		startRoundWait();
		sendBeginEvent();
	}

	@Override
	public void onEnd(GameEndReason reason) {
		if (ended) {
			return;
		}

		roundActive = false;

		Task.tryStopTask(timerTask);
		Task.tryStopTask(messageTask);

		getActiveMap().getStarterLocations().forEach(location -> {
			Firework fw = (Firework) location.getWorld().spawnEntity(location, EntityType.FIREWORK);
			FireworkMeta fwm = fw.getFireworkMeta();

			fwm.setPower(2);
			fwm.addEffect(RandomFireworkEffect.randomFireworkEffect());

			fw.setFireworkMeta(fwm);
		});

		Bukkit.getServer().getOnlinePlayers().forEach(player -> {
			player.setHealth(player.getMaxHealth());
			player.setFoodLevel(20);
			PlayerUtils.clearPlayerInventory(player);
			PlayerUtils.resetPlayerXP(player);
			player.setGameMode(GameMode.SPECTATOR);
			VersionIndependantUtils.get().playSound(player, player.getLocation(), VersionIndependantSound.WITHER_DEATH, 1F, 1F);
		});

		ended = true;
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onEntityDamageByEntity(EntityDamageByEntityEvent e) {
		e.setDamage(0);
		if (e.getEntity() instanceof Player) {
			if (e.getDamager() instanceof Player) {
				Player player = (Player) e.getEntity();
				Player damager = (Player) e.getDamager();

				if (taggedPlayers.contains(damager.getUniqueId())) {
					if (!taggedPlayers.contains(player.getUniqueId())) {
						untagPlayer(damager);
						tagPlayer(player, damager);
					}
				}
			}
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onEntityDamage(EntityDamageEvent e) {
		if (e.getEntity() instanceof Player) {
			e.setDamage(0);
		}
	}

	@EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
	public void onPlayerDropItem(PlayerDropItemEvent e) {
		if (hasStarted()) {
			if (e.getPlayer().getGameMode() != GameMode.CREATIVE) {
				e.setCancelled(true);
			}
		}
	}

	@EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
	public void onBlockPlace(BlockPlaceEvent e) {
		if (hasStarted()) {
			if (e.getPlayer().getGameMode() != GameMode.CREATIVE) {
				e.setCancelled(true);
			}
		}
	}

	@EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
	public void onBlockBreak(BlockBreakEvent e) {
		if (hasStarted()) {
			if (e.getPlayer().getGameMode() != GameMode.CREATIVE) {
				e.setCancelled(true);
			}
		}
	}
}