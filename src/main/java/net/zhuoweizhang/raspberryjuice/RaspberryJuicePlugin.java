package net.zhuoweizhang.raspberryjuice;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Hashtable;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;

public class RaspberryJuicePlugin extends JavaPlugin implements Listener {

	public static final Set<Material> blockBreakDetectionTools = EnumSet.of(
			Material.DIAMOND_SWORD,
			Material.GOLD_SWORD, 
			Material.IRON_SWORD, 
			Material.STONE_SWORD, 
			Material.WOOD_SWORD);

	public ServerListenerThread serverThread;

	public List<RemoteSession> sessions;

	public Player hostPlayer = null;

    public Hashtable<String, Integer> perPlayerCommandQuota = new Hashtable<String, Integer>();

    public int commandQuota = 0;

    public int sustainedCommandQuota = 0;

    public int maxCommandsPerPlayer = 100;

    public int maxCommandsPerTick = 1000;

    public int maxSustainedCommands = 5000;

    public int maxBlocks = 1000;

    public int sustainedTicks = 100;

    public int ticksSustained = 0;

    public int maxDistance = 1535;

    public boolean allowHostlessCommands = true;

    public Hashtable<Integer, Integer> sustainedBlocksQuota = new Hashtable<Integer, Integer>();

    public Hashtable<Integer, Integer> maxSustainedBlocks = new Hashtable<Integer, Integer>();

	private LocationType locationType;

	private HitClickType hitClickType;
	public LocationType getLocationType() {
		return locationType;
	}
	public HitClickType getHitClickType() {
		return hitClickType;
	}

	public void onEnable() {
		//save a copy of the default config.yml if one is not there
        this.saveDefaultConfig();
        //get host and port from config.yml
		String hostname = this.getConfig().getString("hostname");
		if (hostname == null || hostname.isEmpty()) hostname = "0.0.0.0";
		int port = this.getConfig().getInt("port");
		getLogger().info("Using host:port - " + hostname + ":" + Integer.toString(port));
		
		//get location type (ABSOLUTE or RELATIVE) from config.yml
		String location = this.getConfig().getString("location").toUpperCase();
		try {
			locationType = LocationType.valueOf(location);
		} catch(IllegalArgumentException e) {
			getLogger().warning("warning - location value in config.yml should be ABSOLUTE or RELATIVE - '" + location + "' found");
			locationType = LocationType.valueOf("RELATIVE");
		}
		getLogger().info("Using " + locationType.name() + " locations");

		//get hit click type (LEFT, RIGHT or BOTH) from config.yml
		String hitClick = this.getConfig().getString("hitclick").toUpperCase();
		try {
			hitClickType = HitClickType.valueOf(hitClick);
		} catch(IllegalArgumentException e) {
			getLogger().warning("warning - hitclick value in config.yml should be LEFT, RIGHT or BOTH - '" + hitClick + "' found");
			hitClickType = HitClickType.valueOf("RIGHT");
		}
		getLogger().info("Using " + hitClickType.name() + " clicks for hits");

        maxCommandsPerTick = this.getConfig().getInt("maxcommandspertick");
        maxCommandsPerPlayer = this.getConfig().getInt("maxcommandsperplayer");
        maxSustainedCommands = this.getConfig().getInt("maxsustainedcommands");
        sustainedTicks = this.getConfig().getInt("sustainedticks");
        maxDistance = this.getConfig().getInt("maxdistance");
        maxBlocks = this.getConfig().getInt("maxblocks");

        allowHostlessCommands = this.getConfig().getBoolean("allowhostlesscommands");

        List<Integer> blockLimitList = this.getConfig().getIntegerList("blocklimitlist");
        List<Integer> blockLimits = this.getConfig().getIntegerList("blocklimits");
        int size = Math.min(blockLimitList.size(), blockLimits.size());
        for (int i = 0; i < size; i++) {
            sustainedBlocksQuota.put(blockLimitList.get(i), 0);
            maxSustainedBlocks.put(blockLimitList.get(i), blockLimits.get(i));
        }

        this.getCommand("mcpi").setExecutor(new McpiCommandExecutor(this));

		//setup session array
		sessions = new ArrayList<RemoteSession>();
		
		//create new tcp listener thread
		try {
			if (hostname.equals("0.0.0.0")) {
				serverThread = new ServerListenerThread(this, new InetSocketAddress(port));
			} else {
				serverThread = new ServerListenerThread(this, new InetSocketAddress(hostname, port));
			}
			new Thread(serverThread).start();
			getLogger().info("ThreadListener Started");
		} catch (Exception e) {
			e.printStackTrace();
			getLogger().warning("Failed to start ThreadListener");
			return;
		}
		//register the events
		getServer().getPluginManager().registerEvents(this, this);
		//setup the schedule to called the tick handler
		getServer().getScheduler().scheduleSyncRepeatingTask(this, new TickHandler(), 1, 1);
	}
	
	@EventHandler
	public void PlayerJoin(PlayerJoinEvent event) {
		Player p = event.getPlayer();
		//p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 2, true, false));	// give night vision power
		Server server = getServer();
		server.broadcastMessage("Welcome " + p.getPlayerListName());
	}

	@EventHandler(ignoreCancelled=true)
	public void onPlayerInteract(PlayerInteractEvent event) {
		// only react to events which are of the correct type
		switch(hitClickType) {
			case BOTH:
				if ((event.getAction() != Action.RIGHT_CLICK_BLOCK) && (event.getAction() != Action.LEFT_CLICK_BLOCK)) return;
				break;
			case LEFT:
				if (event.getAction() != Action.LEFT_CLICK_BLOCK) return;
				break;
			case RIGHT:
				if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
				break;
		}
		ItemStack currentTool = event.getItem();
		if (currentTool == null || !blockBreakDetectionTools.contains(currentTool.getType())) {
			return;
		}
		for (RemoteSession session: sessions) {
			session.queuePlayerInteractEvent(event);
		}
	}

	@EventHandler(ignoreCancelled=true)
	public void onChatPosted(AsyncPlayerChatEvent event) {
		//debug
		//getLogger().info("Chat event fired");
		for (RemoteSession session: sessions) {
			session.queueChatPostedEvent(event);
		}
	}
	
	@EventHandler(ignoreCancelled=true)
	public void onProjectileHit(ProjectileHitEvent event) {
		
		for (RemoteSession session: sessions) {
			session.queueProjectileHitEvent(event);
		}
	}

	/** called when a new session is established. */
	public void handleConnection(RemoteSession newSession) {
		if (checkBanned(newSession)) {
			getLogger().warning("Kicking " + newSession.getSocket().getRemoteSocketAddress() + " because the IP address has been banned.");
			newSession.kick("You've been banned from this server!");
			return;
		}
		synchronized(sessions) {
			sessions.add(newSession);
		}
	}

	public Player getNamedPlayer(String name) {
		if (name == null) return null;
		for(Player player : Bukkit.getOnlinePlayers()) {
			if (name.equals(player.getPlayerListName())) {
				return player;
			}
		}
		return null;
	}

    public Player getPlayerAtAddress(InetAddress address) {
        if (address == null) return null;
        for(Player player : Bukkit.getOnlinePlayers()) {
            if(address.equals(player.getAddress().getAddress())) {
                return player;
            }
        }
        return null;
    }

	public Player getHostPlayer() {
		if (hostPlayer != null) return hostPlayer;
		for(Player player : Bukkit.getOnlinePlayers()) {
			return player;
		}
		return null;
	}

	//get entity by id - DONE to be compatible with the pi it should be changed to return an entity not a player...
	public Entity getEntity(int id) {
		for (Player p: getServer().getOnlinePlayers()) {
			if (p.getEntityId() == id) {
				return p;
			}
		}
		//check all entities in host player's world
		Player player = getHostPlayer();
		World w = player.getWorld();
		for (Entity e : w.getEntities()) {
			if (e.getEntityId() == id) {
				return e;
			}
		}
		return null;
	}

	public boolean checkBanned(RemoteSession session) {
		Set<String> ipBans = getServer().getIPBans();
		String sessionIp = session.getSocket().getInetAddress().getHostAddress();
		return ipBans.contains(sessionIp);
	}


	public void onDisable() {
		getServer().getScheduler().cancelTasks(this);
		for (RemoteSession session: sessions) {
			try {
				session.close();
			} catch (Exception e) {
				getLogger().warning("Failed to close RemoteSession");
				e.printStackTrace();
			}
		}
		serverThread.running = false;
		try {
			serverThread.serverSocket.close();
		} catch (Exception e) {
			e.printStackTrace();
		}

		sessions = null;
		serverThread = null;
		getLogger().info("Raspberry Juice Stopped");
	}

	private class TickHandler implements Runnable {
		public void run() {
            commandQuota = 0;
            perPlayerCommandQuota.replaceAll((name, quota) -> 0);
            sustainedTicks++;
            if (sustainedTicks >= ticksSustained) {
                sustainedTicks = 0;
                sustainedCommandQuota = 0;
                sustainedBlocksQuota.replaceAll((block, quota) -> 0);
            }
			Iterator<RemoteSession> sI = sessions.iterator();
			while(sI.hasNext()) {
				RemoteSession s = sI.next();
				if (s.pendingRemoval) {
					s.close();
					sI.remove();
				} else {
					s.tick();
				}
			}
		}
	}

    private class McpiCommandExecutor implements CommandExecutor {
        private final RaspberryJuicePlugin plugin;

        public McpiCommandExecutor(RaspberryJuicePlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            /*if (sender instanceof Player && !((Player)sender).hasPermission("mcpi.commands")) {
                sender.sendMessage("You do not have permission to run this command.");
                return true;
            }*/

            if (args.length < 1) {
                return false;
            }

            boolean set = args[0].equals("set");
            boolean get = args[0].equals("get");

            if (!set && !get) {
                return false;
            }

            String cmd = args[1];

            if (set) {
                if (args.length < 3) {
                    sender.sendMessage("/mcpi <get/set> <setting-name> [<set-value1> [<set-value2]]");
                    sender.sendMessage("Settings List:");
                    sender.sendMessage("maxCommandsPerPlayer");
                    sender.sendMessage("maxCommandsPerTick");
                    sender.sendMessage("maxSustainedCommands");
                    sender.sendMessage("sustainedTicks");
                    sender.sendMessage("maxDistance");
                    sender.sendMessage("maxBlocks");
                    sender.sendMessage("allowHostlessCommands");
                    sender.sendMessage("blockLimits");
                    return true;
                }
                
                int value;
                boolean bValue;

                get = true;


                if (cmd.equals("maxCommandsPerPlayer")) {
                    try {
                        value = Integer.parseInt(args[2]);
                    } catch (Exception e) {
                        sender.sendMessage("Invalid parameter: " + args[2]);
                        return true;
                    }
                    plugin.maxCommandsPerPlayer = value;

                } else if (cmd.equals("maxCommandsPerTick")) {
                    try {
                        value = Integer.parseInt(args[2]);
                    } catch (Exception e) {
                        sender.sendMessage("Invalid parameter: " + args[2]);
                        return true;
                    }
                    plugin.maxCommandsPerTick = value;

                } else if (cmd.equals("maxSustainedCommands")) {
                    try {
                        value = Integer.parseInt(args[2]);
                    } catch (Exception e) {
                        sender.sendMessage("Invalid parameter: " + args[2]);
                        return true;
                    }
                    plugin.maxSustainedCommands = value;

                } else if (cmd.equals("sustainedTicks")) {
                    try {
                        value = Integer.parseInt(args[2]);
                    } catch (Exception e) {
                        sender.sendMessage("Invalid parameter: " + args[2]);
                        return true;
                    }
                    plugin.sustainedTicks = value;

                } else if (cmd.equals("maxDistance")) {
                    try {
                        value = Integer.parseInt(args[2]);
                    } catch (Exception e) {
                        sender.sendMessage("Invalid parameter: " + args[2]);
                        return true;
                    }
                    plugin.maxDistance = value;

                } else if (cmd.equals("maxBlocks")) {
                    try {
                        value = Integer.parseInt(args[2]);
                    } catch (Exception e) {
                        sender.sendMessage("Invalid parameter: " + args[2]);
                        return true;
                    }
                    plugin.maxBlocks = value;

                } else if (cmd.equals("allowHostlessCommands")) {
                    try {
                        bValue = Boolean.parseBoolean(args[2]);
                    } catch (Exception e) {
                        sender.sendMessage("Invalid parameter: " + args[2]);
                        return true;
                    }
                    plugin.allowHostlessCommands = bValue;

                } else if (cmd.equals("allowHostlessCommands")) {
                    sender.sendMessage("Allow API calls when no Players are Logged In: " + plugin.allowHostlessCommands);

                } else if (cmd.equals("blockLimits")) {
                    if (args.length < 4) {
                        return false;
                    } 

                    int blockid;
                    try {
                        blockid = Integer.parseInt(args[2]);
                    } catch (Exception e) {
                        sender.sendMessage("Invalid parameter: " + args[2]);
                        return true;
                    }
                    try {
                        value = Integer.parseInt(args[3]);
                    } catch (Exception e) {
                        sender.sendMessage("Invalid parameter: " + args[3]);
                        return true;
                    }

                    plugin.maxSustainedBlocks.put(blockid, value);
                    
                } else {
                    return false;
                }
            }
            
            if (get) {
                if (args.length < 2) {
                    sender.sendMessage("/mcpi <get/set> <setting-name> [<set-value1> [<set-value2]]");
                    sender.sendMessage("Settings List:");
                    sender.sendMessage("maxCommandsPerPlayer");
                    sender.sendMessage("maxCommandsPerTick");
                    sender.sendMessage("maxSustainedCommands");
                    sender.sendMessage("sustainedTicks");
                    sender.sendMessage("maxDistance");
                    sender.sendMessage("maxBlocks");
                    sender.sendMessage("allowHostlessCommands");
                    sender.sendMessage("blockLimits");
                    return true;
                }
                
                if (cmd.equals("maxCommandsPerPlayer")) {
                    sender.sendMessage("Max Commands per Player per Tick: " + plugin.maxCommandsPerPlayer);
                    return true;

                } else if (cmd.equals("maxCommandsPerTick")) {
                    sender.sendMessage("Max Commands per Tick: " + plugin.maxCommandsPerTick);
                    return true;

                } else if (cmd.equals("maxSustainedCommands")) {
                    sender.sendMessage("Max Commands per Sustained Period: " + plugin.maxSustainedCommands);
                    return true;

                } else if (cmd.equals("sustainedTicks")) {
                    sender.sendMessage("Ticks per Sustained Period: " + plugin.sustainedTicks);
                    return true;

                } else if (cmd.equals("maxDistance")) {
                    sender.sendMessage("Maximum Radius from Player or Spawn to Teleport or Place Blocks: " + plugin.maxDistance);
                    return true;

                } else if (cmd.equals("maxBlocks")) {
                    sender.sendMessage("Maximum Number of Blocks in a setBlocks command: " + plugin.maxBlocks);
                    return true;

                } else if (cmd.equals("allowHostlessCommands")) {
                    sender.sendMessage("Allow API calls when no Players are Logged In: " + plugin.allowHostlessCommands);
                    return true;

                } else if (cmd.equals("blockLimits")) {
                    if (args.length == 2) {
                        sender.sendMessage("Block Specific Limits per Sustained Period"); 
                        plugin.maxSustainedBlocks.forEach(
                                (blockid, limit) -> sender.sendMessage("Block:" + blockid + " Limit:" + limit));
                    } else {
                        sender.sendMessage("Block Specific Limits per Sustained Period");
                        int blockid;
                        try {
                            blockid = Integer.parseInt(args[2]);
                        } catch (Exception e) {
                            sender.sendMessage("Invalid parameter: " + args[2]);
                            return true;
                        }
                        if (plugin.maxSustainedBlocks.containsKey(blockid)) {
                            sender.sendMessage("Block Specific Limits per Sustained Period"); 
                            sender.sendMessage("Block:" + blockid + " Limit:" + plugin.maxSustainedBlocks.get(blockid));
                        } else {
                            sender.sendMessage("No Block Specific Limit for Block:" + blockid);
                        }
                    }
                    return true;
                }
            }

            return false;
        }
    }
}
