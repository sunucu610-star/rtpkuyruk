package tr.rtpkuyruk;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * RTPKuyruk - DonutSMP tarzi rastgele isinlanma kuyrugu.
 * /rtpkuyruk (veya /rtpq) yaz, menuden "Evet" de, baska bir oyuncuyla eslesip
 * ikiniz de ayni rastgele noktaya isinlan.
 */
public final class RTPKuyruk extends JavaPlugin implements Listener, CommandExecutor {

    private static final String[] DISKLER = {
            "music_disc.strad", "music_disc.wait", "music_disc.mellohi",
            "music_disc.chirp", "music_disc.stal"
    };

    /** Uzerine / icine isinlanilmamasi gereken bloklar (Material adi olarak). */
    private static final Set<String> TEHLIKELI = Set.of(
            "CACTUS", "MAGMA_BLOCK", "FIRE", "SOUL_FIRE", "CAMPFIRE", "SOUL_CAMPFIRE",
            "SWEET_BERRY_BUSH", "POWDER_SNOW", "LAVA", "WATER", "COBWEB", "WITHER_ROSE",
            "POINTED_DRIPSTONE", "BEDROCK"
    );

    private static final int MENU_HAYIR = 11;
    private static final int MENU_EVET = 15;

    /** Kuyrukta bekleyenler: oyuncu -> gecen saniye. Sira korunur. */
    private final Map<UUID, Integer> bekleyenler = new LinkedHashMap<>();
    /** Kuyrukta caldigimiz muzik diski: oyuncu -> ses adi. */
    private final Map<UUID, String> muzikler = new HashMap<>();
    /** Eslesmis (geri sayimdaki) oyuncular. Iki oyuncu da ayni nesneye bakar. */
    private final Map<UUID, Esles> eslesmeler = new HashMap<>();

    private BukkitTask aramaGorevi;

    private static final class Esles {
        final UUID a;
        final UUID b;
        BukkitTask gorev;

        Esles(UUID a, UUID b) {
            this.a = a;
            this.b = b;
        }
    }

    private static final class MenuSahibi implements InventoryHolder {
        Inventory envanter;

        @Override
        public Inventory getInventory() {
            return envanter;
        }
    }

    // ------------------------------------------------------------------
    // Eklenti acilis / kapanis
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand komut = getCommand("rtpkuyruk");
        if (komut != null) {
            komut.setExecutor(this);
        }
        aramaGorevi = getServer().getScheduler().runTaskTimer(this, this::aramaTick, 20L, 20L);
    }

    @Override
    public void onDisable() {
        if (aramaGorevi != null) {
            aramaGorevi.cancel();
        }
        getServer().getScheduler().cancelTasks(this);
        for (UUID id : new ArrayList<>(muzikler.keySet())) {
            muzikDurdur(Bukkit.getPlayer(id));
        }
        bekleyenler.clear();
        muzikler.clear();
        eslesmeler.clear();
    }

    // ------------------------------------------------------------------
    // Komut
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player oyuncu)) {
            sender.sendMessage("Bu komutu sadece oyuncular kullanabilir.");
            return true;
        }
        UUID id = oyuncu.getUniqueId();

        if (args.length > 0 && args[0].equalsIgnoreCase("iptal")) {
            if (bekleyenler.containsKey(id) || eslesmeler.containsKey(id)) {
                muzikDurdur(oyuncu);
                ayril(id);
                oyuncu.sendActionBar(Component.empty());
                oyuncu.sendMessage(mesaj("Kuyruktan çıktın.", NamedTextColor.YELLOW));
            } else {
                oyuncu.sendMessage(mesaj("Zaten kuyrukta değilsin.", NamedTextColor.RED));
            }
            return true;
        }

        if (bekleyenler.containsKey(id) || eslesmeler.containsKey(id)) {
            oyuncu.sendMessage(mesaj("Zaten kuyruktasın. İptal etmek için /" + label + " iptal yaz.",
                    NamedTextColor.RED));
            return true;
        }

        menuAc(oyuncu);
        return true;
    }

    // ------------------------------------------------------------------
    // Menu
    // ------------------------------------------------------------------

    private void menuAc(Player oyuncu) {
        MenuSahibi sahip = new MenuSahibi();
        Inventory envanter = Bukkit.createInventory(sahip, 27, Component.text("Rastgele Işınlanma Kuyruğu"));
        sahip.envanter = envanter;

        ItemStack dolgu = oge(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < 27; i++) {
            envanter.setItem(i, dolgu);
        }

        envanter.setItem(MENU_HAYIR, oge(Material.RED_STAINED_GLASS_PANE,
                Component.text("Hayır", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(Component.text("Menüyü kapatır.", NamedTextColor.GRAY))));

        envanter.setItem(13, oge(Material.DIAMOND_SWORD,
                Component.text("Rastgele Rakiple Işınlan", NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(
                        Component.text("Kuyruğa girersin, başka bir oyuncu", NamedTextColor.GRAY),
                        Component.text("gelince ikiniz de aynı rastgele", NamedTextColor.GRAY),
                        Component.text("noktaya ışınlanırsınız.", NamedTextColor.GRAY))));

        envanter.setItem(MENU_EVET, oge(Material.GREEN_STAINED_GLASS_PANE,
                Component.text("Evet", NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(Component.text("Kuyruğa gir ve rakip ara.", NamedTextColor.GRAY))));

        oyuncu.openInventory(envanter);
    }

    private ItemStack oge(Material madde, Component ad, List<Component> aciklama) {
        ItemStack esya = new ItemStack(madde);
        ItemMeta meta = esya.getItemMeta();
        meta.displayName(ad.decoration(TextDecoration.ITALIC, false));
        List<Component> satirlar = new ArrayList<>();
        for (Component satir : aciklama) {
            satirlar.add(satir.decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(satirlar);
        esya.setItemMeta(meta);
        return esya;
    }

    @EventHandler
    public void onTikla(InventoryClickEvent olay) {
        if (!(olay.getInventory().getHolder() instanceof MenuSahibi)) {
            return;
        }
        olay.setCancelled(true);
        if (!(olay.getWhoClicked() instanceof Player oyuncu)) {
            return;
        }
        if (olay.getClickedInventory() != olay.getInventory()) {
            return;
        }
        int yuva = olay.getSlot();
        if (yuva == MENU_EVET) {
            getServer().getScheduler().runTask(this, oyuncu::closeInventory);
            kuyrugaGir(oyuncu);
        } else if (yuva == MENU_HAYIR) {
            getServer().getScheduler().runTask(this, oyuncu::closeInventory);
        }
    }

    @EventHandler
    public void onSurukle(InventoryDragEvent olay) {
        if (olay.getInventory().getHolder() instanceof MenuSahibi) {
            olay.setCancelled(true);
        }
    }

    @EventHandler
    public void onCik(PlayerQuitEvent olay) {
        ayril(olay.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------
    // Kuyruk mantigi
    // ------------------------------------------------------------------

    private void kuyrugaGir(Player oyuncu) {
        UUID id = oyuncu.getUniqueId();
        if (bekleyenler.containsKey(id) || eslesmeler.containsKey(id)) {
            return;
        }
        bekleyenler.put(id, 0);
        muzikCal(oyuncu);
        oyuncu.sendMessage(mesaj("Kuyruğa girdin, rakip aranıyor... İptal için /rtpkuyruk iptal",
                NamedTextColor.GRAY));
        eslestir();
    }

    /** Kuyruktan ve eslesmeden cikarir. Eslesmedeki diger oyuncu aramaya geri doner. */
    private void ayril(UUID id) {
        bekleyenler.remove(id);
        muzikler.remove(id);
        Esles es = eslesmeler.get(id);
        if (es != null) {
            eslesmeBozul(es, id);
        }
    }

    private void eslesmeBozul(Esles es, UUID ayrilan) {
        if (es.gorev != null) {
            es.gorev.cancel();
        }
        eslesmeler.remove(es.a);
        eslesmeler.remove(es.b);
        for (UUID id : new UUID[]{es.a, es.b}) {
            if (id.equals(ayrilan)) {
                continue;
            }
            Player diger = Bukkit.getPlayer(id);
            if (diger != null && diger.isOnline()) {
                diger.sendMessage(mesaj("Rakibin ayrıldı, aramaya devam ediliyor...", NamedTextColor.YELLOW));
                bekleyenler.put(id, 0);
                if (!muzikler.containsKey(id)) {
                    muzikCal(diger);
                }
            }
        }
        eslestir();
    }

    /** Kuyrukta en az iki kisi varsa ilk ikisini eslestirir. */
    private void eslestir() {
        while (bekleyenler.size() >= 2) {
            Iterator<UUID> sira = bekleyenler.keySet().iterator();
            UUID a = sira.next();
            UUID b = sira.next();
            Player pa = Bukkit.getPlayer(a);
            Player pb = Bukkit.getPlayer(b);
            if (pa == null) {
                bekleyenler.remove(a);
                muzikler.remove(a);
                continue;
            }
            if (pb == null) {
                bekleyenler.remove(b);
                muzikler.remove(b);
                continue;
            }
            bekleyenler.remove(a);
            bekleyenler.remove(b);
            Esles es = new Esles(a, b);
            eslesmeler.put(a, es);
            eslesmeler.put(b, es);
            geriSayimBaslat(es, pa, pb);
        }
    }

    private void geriSayimBaslat(Esles es, Player pa, Player pb) {
        pa.sendMessage(mesaj("Rakip bulundu: " + pb.getName() + "! Hazırlan...", NamedTextColor.GREEN));
        pb.sendMessage(mesaj("Rakip bulundu: " + pa.getName() + "! Hazırlan...", NamedTextColor.GREEN));

        BukkitRunnable sayac = new BukkitRunnable() {
            int kalan = 3;

            @Override
            public void run() {
                Player x = Bukkit.getPlayer(es.a);
                Player y = Bukkit.getPlayer(es.b);
                if (x == null || y == null) {
                    cancel();
                    eslesmeBozul(es, x == null ? es.a : es.b);
                    return;
                }
                if (kalan > 0) {
                    baslikGoster(x, kalan, y.getName());
                    baslikGoster(y, kalan, x.getName());
                    float perde = 0.8f + (3 - kalan) * 0.3f;
                    ses(x, "block.note_block.pling", 1f, perde);
                    ses(y, "block.note_block.pling", 1f, perde);
                    kalan--;
                } else {
                    cancel();
                    isinla(es);
                }
            }
        };
        es.gorev = sayac.runTaskTimer(this, 0L, 20L);
    }

    private void baslikGoster(Player oyuncu, int sayi, String rakipAdi) {
        Title baslik = Title.title(
                Component.text(String.valueOf(sayi), NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text("Rakip: " + rakipAdi, NamedTextColor.GRAY),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100)));
        oyuncu.showTitle(baslik);
    }

    private void isinla(Esles es) {
        World dunya = hedefDunya();
        konumBul(dunya, 30, konum -> {
            // Konum aranirken biri ayrildiysa bu eslesme zaten bozuldu.
            if (eslesmeler.get(es.a) != es || eslesmeler.get(es.b) != es) {
                return;
            }
            Player pa = Bukkit.getPlayer(es.a);
            Player pb = Bukkit.getPlayer(es.b);
            if (pa == null || pb == null) {
                eslesmeBozul(es, pa == null ? es.a : es.b);
                return;
            }
            eslesmeler.remove(es.a);
            eslesmeler.remove(es.b);

            if (konum == null) {
                for (Player p : new Player[]{pa, pb}) {
                    muzikDurdur(p);
                    p.sendMessage(mesaj("Güvenli bir yer bulunamadı. Lütfen tekrar dene: /rtpkuyruk",
                            NamedTextColor.RED));
                }
                return;
            }

            pa.teleportAsync(konum);
            pb.teleportAsync(konum);
            for (Player p : new Player[]{pa, pb}) {
                muzikDurdur(p);
                p.sendActionBar(Component.empty());
                p.sendMessage(mesaj("Işınlandın! Bol şans!", NamedTextColor.GOLD));
            }
            getServer().getScheduler().runTaskLater(this, () -> {
                for (Player p : new Player[]{pa, pb}) {
                    if (p.isOnline()) {
                        ses(p, "entity.enderman.teleport", 1f, 1f);
                    }
                }
            }, 5L);
        });
    }

    /** Her saniye: bekleyenlere aksiyon cubugu yazisi gosterir, suresi dolani cikarir. */
    private void aramaTick() {
        int sure = getConfig().getInt("bekleme-suresi", 80);
        Iterator<Map.Entry<UUID, Integer>> sira = bekleyenler.entrySet().iterator();
        while (sira.hasNext()) {
            Map.Entry<UUID, Integer> kayit = sira.next();
            Player oyuncu = Bukkit.getPlayer(kayit.getKey());
            if (oyuncu == null) {
                sira.remove();
                muzikler.remove(kayit.getKey());
                continue;
            }
            int gecen = kayit.getValue() + 1;
            if (gecen >= sure) {
                sira.remove();
                muzikDurdur(oyuncu);
                oyuncu.sendActionBar(Component.empty());
                oyuncu.sendMessage(mesaj("Süre doldu, rakip bulunamadı. Tekrar denemek için /rtpkuyruk yaz.",
                        NamedTextColor.RED));
                continue;
            }
            kayit.setValue(gecen);
            oyuncu.sendActionBar(Component.text("Rakip aranıyor... (" + (sure - gecen) + " sn)",
                    NamedTextColor.GRAY));
        }
    }

    // ------------------------------------------------------------------
    // Rastgele guvenli konum bulma
    // ------------------------------------------------------------------

    private World hedefDunya() {
        String ad = getConfig().getString("dunya", "world");
        World dunya = Bukkit.getWorld(ad);
        return dunya != null ? dunya : Bukkit.getWorlds().get(0);
    }

    private void konumBul(World dunya, int hak, Consumer<Location> sonuc) {
        if (hak <= 0) {
            sonuc.accept(null);
            return;
        }
        int yaricap = Math.max(10, getConfig().getInt("yaricap", 5000));
        int merkezX = getConfig().getInt("merkez-x", 0);
        int merkezZ = getConfig().getInt("merkez-z", 0);
        ThreadLocalRandom rastgele = ThreadLocalRandom.current();
        int x = merkezX + rastgele.nextInt(-yaricap, yaricap + 1);
        int z = merkezZ + rastgele.nextInt(-yaricap, yaricap + 1);

        dunya.getChunkAtAsync(x >> 4, z >> 4).whenComplete((parca, hata) ->
                getServer().getScheduler().runTask(this, () -> {
                    Location konum = (hata == null && parca != null) ? guvenliKonum(dunya, x, z) : null;
                    if (konum != null) {
                        sonuc.accept(konum);
                    } else {
                        konumBul(dunya, hak - 1, sonuc);
                    }
                }));
    }

    private Location guvenliKonum(World dunya, int x, int z) {
        if (!dunya.getWorldBorder().isInside(new Location(dunya, x, 0, z))) {
            return null;
        }
        int y = dunya.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (y <= dunya.getMinHeight() + 1) {
            return null;
        }
        Block zemin = dunya.getBlockAt(x, y, z);
        if (zemin.isLiquid() || zemin.isPassable() || tehlikeli(zemin)) {
            return null;
        }
        Block ayak = zemin.getRelative(0, 1, 0);
        Block bas = zemin.getRelative(0, 2, 0);
        if (!bosMu(ayak) || !bosMu(bas)) {
            return null;
        }
        return new Location(dunya, x + 0.5, y + 1, z + 0.5);
    }

    private boolean bosMu(Block blok) {
        return blok.isPassable() && !blok.isLiquid() && !tehlikeli(blok);
    }

    private boolean tehlikeli(Block blok) {
        return TEHLIKELI.contains(blok.getType().name());
    }

    // ------------------------------------------------------------------
    // Yardimcilar: mesaj, ses, muzik
    // ------------------------------------------------------------------

    private static Component mesaj(String yazi, TextColor renk) {
        return Component.text("[RTP] ", NamedTextColor.DARK_PURPLE).append(Component.text(yazi, renk));
    }

    private void ses(Player oyuncu, String ad, float seviye, float perde) {
        oyuncu.playSound(Sound.sound(Key.key(ad), Sound.Source.MASTER, seviye, perde), Sound.Emitter.self());
    }

    private void muzikCal(Player oyuncu) {
        String disk = DISKLER[ThreadLocalRandom.current().nextInt(DISKLER.length)];
        muzikler.put(oyuncu.getUniqueId(), disk);
        oyuncu.playSound(Sound.sound(Key.key(disk), Sound.Source.RECORD, 0.8f, 1f), Sound.Emitter.self());
    }

    private void muzikDurdur(Player oyuncu) {
        if (oyuncu == null) {
            return;
        }
        String disk = muzikler.remove(oyuncu.getUniqueId());
        if (disk != null) {
            oyuncu.stopSound(SoundStop.named(Key.key(disk)));
        }
    }
}
