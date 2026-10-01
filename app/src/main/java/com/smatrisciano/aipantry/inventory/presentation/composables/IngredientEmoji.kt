package com.smatrisciano.aipantry.inventory.presentation.composables

import java.text.Normalizer

/**
 * Emoji per un ingrediente: match per parole chiave sul nome, dalla più
 * specifica alla più generica — con un vocabolario di ~870 voci una mappa
 * esatta nome→emoji non è mantenibile. Fallback neutro 🍽️ solo per ciò che
 * non rientra in nessuna famiglia.
 *
 * Chiavi in inglese e in italiano nello stesso gruppo: i nomi arrivano nella
 * lingua del device (detector, ricette di Gemma) e l'inventario può mescolarle.
 */
fun ingredientEmoji(name: String): String {
    // Senza accenti e con l'apostrofo dritto: "tè", "caffè", "d’oliva" diventano
    // "te", "caffe", "d'oliva", così le chiavi restano ASCII (\b non tratta
    // le lettere accentate come lettere su ogni runtime)
    val n = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace('’', '\'')

    // Match su confini di parola: "ham" NON deve matchare "cHAMpagne"
    fun has(vararg keys: String) = keys.any { key ->
        Regex("\\b${Regex.escape(key)}").containsMatchIn(n)
    }

    // Parola intera, per le chiavi corte che sono prefisso d'altro:
    // "mela" non è "melanzane", "pepe" non è "peperoni", "pane" non è "panettone"
    fun word(vararg keys: String) = keys.any { key ->
        Regex("\\b${Regex.escape(key)}\\b").containsMatchIn(n)
    }

    return when {
        // Specifici prima delle famiglie: "strawberry jam" è 🫙, non 🍓,
        // e "salad dressing" è una salsa, non un'insalata
        has("jam", "marmalade", "chutney", "curd", "spread", "nutella",
            "marmellat", "confettur", "crema spalmabile", "speculoos") -> "🫙"
        has("dressing", "vinaigrette", "condimento per insalat") -> "🥫"
        has("hamburger bun", "hot dog bun", "panini per", "panino per", "pane per") -> "🍞"
        has("hamburger") -> "🥩"
        has("cornett") -> "🥐"
        has("pasta sfoglia", "pasta brise", "pasta frolla", "pasta fillo", "pasta phyllo") -> "🥐"
        has("pasta di zucchero") -> "🧁"
        // "pasta di/d'…" è quasi sempre una pasta condimento, non pasta da cuocere
        has("pasta d'acciughe", "pasta d'aglio", "pasta di miso", "pasta di gamberi", "pasta di zenzero",
            "pasta di curry", "pasta tom yum", "pasta di tamarindo", "pasta di wasabi", "pasta di sesamo",
            "pasta di peperoncino") -> "🥫"
        has("erba cipollina") -> "🌿"
        has("eggplant", "melanzan") -> "🍆"
        has("cherry tomato") -> "🍅"
        has("uova di pesce", "uova di lompo") -> "🐟"
        has("lievito di birra") -> "🥄"
        has("olio di semi", "olio di arachidi", "peanut oil") -> "🫗"
        has("ravioli cinesi") -> "🥟"
        has("fiocchi di latte") -> "🧀"
        has("juice", "smoothie", "lemonade", "succo", "succhi", "spremuta", "frullat", "limonata",
            "centrifugat") -> "🧃"
        has("ice cream", "gelato", "sorbet", "popsicle", "ghiacciol") -> "🍦"
        has("frozen", "surgelat", "congelat") -> "🧊"
        has("canned", "spam", "in scatola", "scatolett", "lattin") || word("can", "cans") -> "🥫"

        // Latticini e uova
        has("milk", "kefir", "buttermilk", "cream", "custard", "pudding",
            "latte", "panna", "budino", "crema pasticcera", "latticello") -> "🥛"
        has("yogurt", "skyr", "quark") -> "🥣"
        has("cheese", "mozzarella", "burrata", "brie", "camembert", "gouda", "edam", "emmental",
            "gruyere", "feta", "halloumi", "gorgonzola", "provolone", "pecorino", "parmesan",
            "parmigiano", "grana", "asiago", "fontina", "taleggio", "stracchino", "robola",
            "robiola", "crescenza", "scamorza", "manchego", "paneer", "ricotta", "mascarpone",
            "caciocavallo", "cotija", "queso", "formagg", "caprino", "provola", "groviera",
            "cheddar") -> "🧀"
        has("butter", "margarine", "ghee", "burro", "margarina") -> "🧈"
        has("egg", "uov") -> "🥚"

        // Frutta ("mela" solo parola intera: non deve coprire "melanzane")
        has("watermelon", "anguria", "cocomer") -> "🍉"
        has("melon", "cantaloupe") -> "🍈"
        has("strawberr", "fragol") -> "🍓"
        has("blueberr", "currant", "goji", "acai", "mulberr", "gooseberr", "cranberr",
            "mirtill", "ribes", "uva spina", "gelso") -> "🫐"
        has("raspberr", "blackberr", "lampon") -> "🍇"
        word("more") -> "🍇"
        has("grape", "uvett", "uva sultanina") || word("uva") -> "🍇"
        has("banana", "plantain", "platan", "banan") -> "🍌"
        has("apple") || word("mela", "mele") -> "🍎"
        word("pear", "pears", "pera", "pere") -> "🍐"
        has("peach", "nectarine", "apricot", "nettarin", "albicocc") || word("pesca", "pesche") -> "🍑"
        has("cherry", "cherries", "ciliegi", "amaren") -> "🍒"
        has("orange", "clementine", "tangerine", "mandarin", "kumquat", "pomelo", "grapefruit",
            "clementin", "pompelm") || word("arancia", "arance") -> "🍊"
        has("lemon", "yuzu", "citron", "limone", "limoni", "cedro") -> "🍋"
        has("lime") -> "🍋"
        has("pineapple", "ananas") -> "🍍"
        has("mango") -> "🥭"
        has("kiwi") -> "🥝"
        has("coconut", "cocco") -> "🥥"
        has("avocado", "guacamole") -> "🥑"
        has("fig", "date", "prune", "raisin", "sultana", "fich", "prugn") ||
            word("dattero", "datteri") -> "🍇"
        has("pomegranate", "persimmon", "quince", "lychee", "passion", "guava", "starfruit",
            "dragon fruit", "papaya", "jackfruit", "physalis", "melagran", "cachi", "kaki",
            "litchi", "frutto della passione", "carambola", "alchechengi", "frutto del drago",
            "melogran", "pitaya") -> "🥭"

        // Verdure
        has("tomato", "passata", "pomodor", "pelati", "datterini") -> "🍅"
        has("potato", "patat") || word("pure") -> "🥔"
        has("carrot", "carot") -> "🥕"
        has("corn", "mais", "pannocchi") -> "🌽"
        has("pepper", "chili", "jalapeno", "habanero", "poblano", "chipotle", "pepperoncini",
            "peperon", "friggitell") -> "🌶️"
        has("cucumber", "zucchini", "pickle", "gherkin", "cornichon",
            "cetriol", "zucchin", "sottacet") -> "🥒"
        has("lettuce", "salad", "spinach", "arugula", "kale", "chard", "greens", "watercress",
            "endive", "radicchio", "frisee", "sorrel", "microgreens", "sprouts", "cress",
            "coleslaw", "bok choy", "cabbage",
            "lattug", "insalat", "spinac", "rucol", "bietol", "crescione", "indivia",
            "acetosa", "germogl", "cavolo", "verza", "valerian", "songino", "misticanza",
            "scarola", "pak choi", "ortagg", "tarassaco", "foglie di senape") -> "🥬"
        has("broccoli", "broccolini", "romanesco", "asparagus", "artichoke", "brussels",
            "celery", "leek", "fennel", "okra",
            "broccol", "romanesc", "asparag", "carciof", "sedano", "finocch", "cavolfior",
            "cavolett", "gombo") || word("porro", "porri") -> "🥦"
        has("onion", "shallot", "scallion", "cipoll", "scalogn") -> "🧅"
        has("garlic", "aglio") -> "🧄"
        has("mushroom", "porcini", "shiitake", "portobello", "enoki", "truffle",
            "fungh", "fungo", "champignon", "tartuf") -> "🍄"
        has("pumpkin", "squash", "zucca") -> "🎃"
        has("ginger", "turnip", "parsnip", "radish", "beet", "celeriac", "kohlrabi", "daikon",
            "horseradish", "zenzero", "pastinac", "ravanell", "barbabietol", "rafano") ||
            word("rapa", "rape") -> "🥕"
        has("edamame", "bean", "lentil", "chickpea", "soy chunk", "tofu", "tempeh",
            "seitan", "falafel", "hummus",
            "pisell", "fagiol", "lenticch", "spezzatino di soia", "lupin", "cicerchi", "taccole") ||
            word("pea", "peas", "ceci", "fave") -> "🫘"
        has("olive", "tapenade", "caper", "oliv", "capper", "cucunci") -> "🫒"

        // Carne e pesce
        has("bacon", "pancetta", "guanciale", "lardo", "speck", "pork", "porchetta",
            "maiale", "lonza", "braciol") -> "🥓"
        has("ham", "prosciutto", "mortadella", "salami", "chorizo", "pepperoni", "bresaola",
            "coppa", "culatello", "pastrami", "deli", "liverwurst", "pate", "nduja",
            "cotechino", "blood sausage", "salame", "affettat", "sanguinacci", "leberwurst") -> "🍖"
        has("sausage", "hot dog", "wurst", "salsicc", "wurstel") -> "🌭"
        has("chicken", "turkey", "duck", "quail", "poultry",
            "pollo", "tacchin", "anatra", "quagli", "faraona") -> "🍗"
        has("beef", "steak", "veal", "brisket", "oxtail", "venison", "lamb", "goat", "rabbit",
            "meatball", "burger", "kebab", "mince", "jerky", "biltong", "ribs",
            "manzo", "bistecc", "vitell", "agnell", "capretto", "coniglio", "polpett",
            "macinato vegetale", "carne", "spezzatino", "costat", "costin", "cervo", "coda di bue") -> "🥩"
        word("cod") || has("salmon", "tuna", "halibut", "trout", "mackerel", "herring", "sardine",
            "anchov", "swordfish", "sea bass", "sea bream", "tilapia", "catfish", "monkfish",
            "snapper", "eel", "fish", "surimi", "roe", "caviar",
            "salmone", "tonno", "merluzz", "baccal", "trota", "sgombr", "aring", "sardin",
            "acciugh", "alici", "pesce", "branzino", "spigola", "orata", "rana pescatrice",
            "dentice", "anguill", "caviale", "bottarga", "platessa", "sogliola", "nasello",
            "ippoglosso", "stoccafisso") -> "🐟"
        has("shrimp", "prawn", "gamber", "mazzancoll", "scamp") -> "🦐"
        has("crab", "granch") -> "🦀"
        has("lobster", "crayfish", "aragost", "astice") -> "🦞"
        has("squid", "octopus", "calamari", "calamar", "polpo", "polpi", "seppi", "totan",
            "moscardin") -> "🦑"
        has("mussel", "clam", "oyster", "scallop", "seafood",
            "cozz", "vongol", "ostric", "capesant", "frutti di mare", "misto mare") -> "🦪"

        // Carboidrati
        has("spaghetti", "linguine", "tagliatelle", "bucatini", "angel hair", "noodle",
            "ramen", "vermicelli",
            "spaghett", "linguin", "tagliatell", "bucatin", "capellini", "capelli d'angelo",
            "fettuccin", "pappardell", "tagliolin", "bigoli", "pici", "soba", "udon") -> "🍜"
        has("pasta", "penne", "fusilli", "rigatoni", "farfalle", "macaroni", "lasagna",
            "cannelloni", "orecchiette", "trofie", "paccheri", "ditalini", "gnocchi",
            "tortellini", "cappelletti", "agnolotti", "ravioli",
            "maccheron", "lasagn", "risoni", "conchigli", "mezze maniche", "tubetti",
            "pastina", "stelline", "caserecce", "strozzapreti", "garganelli", "maltagliati",
            "orecchiett", "tortellin", "cappellett", "agnolott", "raviol", "gnocch") -> "🍝"
        has("rice", "risotto", "paella", "riso", "carnaroli", "arborio", "basmati") -> "🍚"
        has("bread", "baguette", "ciabatta", "focaccia", "sourdough", "brioche", "bun", "bagel",
            "muffin", "crumpet", "scone", "toast", "pita", "naan", "piadina", "carasau",
            "tortilla", "wrap", "taco",
            "panin", "pancarr", "pangrattat", "fette biscottate", "crostin", "frisell",
            "pane carasau", "tortill") || word("pane") -> "🍞"
        has("croissant", "danish", "strudel", "pastry", "pie crust", "sfoglia", "dolce danese") -> "🥐"
        has("pizza") -> "🍕"
        has("pretzel", "breadstick", "taralli", "cracker", "crisp", "chips", "nachos",
            "grissin", "salatin") -> "🥨"
        has("flour", "semolina", "cornmeal", "polenta", "couscous", "quinoa", "barley",
            "buckwheat", "bulgur", "millet", "farro", "freekeh", "oat", "muesli", "granola",
            "cereal", "bran", "cornflake",
            "farin", "semol", "cuscus", "orzo", "grano saraceno", "miglio", "avena",
            "cereali", "crusca", "corn flakes", "porridge") -> "🌾"

        // Dolci e snack
        has("chocolate", "cocoa", "brownie", "cioccolat", "cacao") -> "🍫"
        has("cookie", "biscotti", "shortbread", "wafer", "ladyfinger",
            "biscott", "frollin", "savoiard", "amarett", "cantucci") -> "🍪"
        has("cake", "panettone", "pandoro", "tiramisu", "cheesecake", "cannoli", "eclair",
            "macaron", "meringue", "profiterole", "cinnamon roll", "donut", "churro",
            "sfogliatelle", "tart", "pie",
            "torta", "torte", "tiramis", "cannol", "bign", "meringh", "girell", "ciambell",
            "krapfen", "bombolon", "crostat", "colomba", "pan di spagna", "plumcake") -> "🍰"
        has("candy", "marshmallow", "gummy", "licorice",
            "caramell", "gommos", "liquirizi", "confett") -> "🍬"
        has("honey", "syrup", "molasses", "agave", "miele", "sciropp", "melassa") -> "🍯"
        has("sugar", "sprinkles", "marzipan", "fondant", "vanilla",
            "zucchero", "codette", "marzapane", "vanigli") -> "🧁"
        has("popcorn", "pop corn") -> "🍿"
        has("almond", "walnut", "peanut", "cashew", "pistachio", "hazelnut", "pecan",
            "macadamia", "pine nut", "nut", "trail mix", "seeds",
            "mandorl", "arachid", "anacardi", "pistacchi", "nocciol", "pinoli",
            "frutta secca") || word("noci", "noce", "semi") -> "🥜"

        // Bevande
        has("coffee", "espresso", "caffe") -> "☕"
        has("tea", "matcha", "chamomile", "rooibos", "kombucha", "tisan", "camomill") ||
            word("te") -> "🍵"
        has("beer", "cider", "birr", "sidro") -> "🍺"
        has("champagne", "prosecco", "spumante") -> "🍾"
        has("wine", "vermouth", "aperol", "campari", "sake", "limoncello", "grappa",
            "vino", "vini", "marsala", "amaro") -> "🍷"
        has("water", "soda", "cola", "tonic", "ginger ale", "ginger beer", "root beer",
            "energy drink", "protein shake", "shake",
            "acqua", "bibit", "aranciata", "gassos", "chinotto") -> "🥤"

        // Condimenti e dispensa
        has("oil", "olio") -> "🫗"
        has("vinegar", "dressing", "vinaigrette", "mayo", "aioli", "ketchup", "mustard",
            "sauce", "pesto", "salsa", "tahini", "miso", "gochujang", "harissa", "paste",
            "relish", "sriracha", "tzatziki", "chimichurri", "ponzu", "mirin", "dashi",
            "aceto", "maionese", "senape", "sugo", "sughi", "concentrato") -> "🥫"
        has("salt", "pepper", "paprika", "cumin", "turmeric", "curry", "cinnamon", "nutmeg",
            "clove", "cardamom", "saffron", "spice", "seasoning", "zaatar", "masala",
            "anise", "peppercorn", "flake",
            "cumino", "curcuma", "cannella", "chiodi di garofano", "cardamomo",
            "zafferano", "spezi", "anice", "insaporitor", "za'atar") || word("sale", "pepe") -> "🧂"
        has("basil", "parsley", "rosemary", "mint", "cilantro", "thyme", "oregano", "sage",
            "dill", "chives", "tarragon", "bay lea", "lemongrass", "herb",
            "basilic", "prezzemol", "rosmarin", "menta", "coriandol", "timo", "origano",
            "salvia", "aneto", "erba cipollina", "dragoncell", "alloro", "citronella",
            "erbe") -> "🌿"
        has("broth", "stock", "bouillon", "soup", "minestrone",
            "brodo", "dado", "dadi", "zupp", "minestr", "vellutat") -> "🍲"
        has("yeast", "baking", "cornstarch", "gelatin", "protein powder",
            "lievit", "amido", "fecola", "gelatina", "proteine in polvere", "bicarbonato") -> "🥄"
        has("seaweed", "nori", "wakame", "kombu", "furikake", "wasabi", "alghe") -> "🍙"
        has("kimchi", "sauerkraut", "dumpling", "gyoza", "pierogi", "spring roll", "samosa",
            "wonton", "dolma", "arancini", "crauti", "involtini primavera", "suppl",
            "foglie di vite") -> "🥟"

        else -> "🍽️"
    }
}
