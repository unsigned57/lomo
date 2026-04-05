package com.lomo.data.s3

private const val BASE32768_BLOCK_BIT = 5
private const val BASE32768_INVALID = 0xFFFD
private const val BASE32768_SAFE_ALPHABET =
    "ƀɀɠʀҠԀڀڠݠހ߀ကႠᄀᄠᅀᆀᇠሀሠበዠጠᎠᏀᐠᑀᑠᒀᒠᓀᓠᔀᔠᕀᕠᖀᖠᗀᗠᘀᘠᙀᚠᛀកᠠᡀᣀᦀ᧠ᨠᯀᰀᴀ⇠⋀⍀⍠⎀⎠⏀␀─┠╀╠▀■◀◠☀☠♀♠⚀⚠⛀⛠✀✠❀➀➠⠀⠠⡀⡠⢀⢠⣀⣠⤀⤠⥀⥠⦠⨠⩀⪀⪠⫠⬀⬠⭀ⰀⲀⲠⳀⴀⵀ⺠⻀㇀㐀㐠㑀㑠㒀㒠㓀㓠㔀㔠㕀㕠㖀㖠㗀㗠㘀㘠㙀㙠㚀㚠㛀㛠㜀㜠㝀㝠㞀㞠㟀㟠㠀㠠㡀㡠㢀㢠㣀㣠㤀㤠㥀㥠㦀㦠㧀㧠㨀㨠㩀㩠㪀㪠㫀㫠㬀㬠㭀㭠㮀㮠㯀㯠㰀㰠㱀㱠㲀㲠㳀㳠㴀㴠㵀㵠㶀㶠㷀㷠㸀㸠㹀㹠㺀㺠㻀㻠㼀㼠㽀㽠㾀㾠㿀㿠䀀䀠䁀䁠䂀䂠䃀䃠䄀䄠䅀䅠䆀䆠䇀䇠䈀䈠䉀䉠䊀䊠䋀䋠䌀䌠䍀䍠䎀䎠䏀䏠䐀䐠䑀䑠䒀䒠䓀䓠䔀䔠䕀䕠䖀䖠䗀䗠䘀䘠䙀䙠䚀䚠䛀䛠䜀䜠䝀䝠䞀䞠䟀䟠䠀䠠䡀䡠䢀䢠䣀䣠䤀䤠䥀䥠䦀䦠䧀䧠䨀䨠䩀䩠䪀䪠䫀䫠䬀䬠䭀䭠䮀䮠䯀䯠䰀䰠䱀䱠䲀䲠䳀䳠䴀䴠䵀䵠䶀䷀䷠一丠乀习亀亠什仠伀传佀你侀侠俀俠倀倠偀偠傀傠僀僠儀儠兀兠冀冠净几刀删剀剠劀加勀勠匀匠區占厀厠叀叠吀吠呀呠咀咠哀哠唀唠啀啠喀喠嗀嗠嘀嘠噀噠嚀嚠囀因圀圠址坠垀垠埀埠堀堠塀塠墀墠壀壠夀夠奀奠妀妠姀姠娀娠婀婠媀媠嫀嫠嬀嬠孀孠宀宠寀寠尀尠局屠岀岠峀峠崀崠嵀嵠嶀嶠巀巠帀帠幀幠庀庠廀廠开张彀彠往徠忀忠怀怠恀恠悀悠惀惠愀愠慀慠憀憠懀懠戀戠所扠技抠拀拠挀挠捀捠掀掠揀揠搀搠摀摠撀撠擀擠攀攠敀敠斀斠旀无昀映晀晠暀暠曀曠最朠杀杠枀枠柀柠栀栠桀桠梀梠检棠椀椠楀楠榀榠槀槠樀樠橀橠檀檠櫀櫠欀欠歀歠殀殠毀毠氀氠汀池沀沠泀泠洀洠浀浠涀涠淀淠渀渠湀湠満溠滀滠漀漠潀潠澀澠激濠瀀瀠灀灠炀炠烀烠焀焠煀煠熀熠燀燠爀爠牀牠犀犠狀狠猀猠獀獠玀玠珀珠琀琠瑀瑠璀璠瓀瓠甀甠畀畠疀疠痀痠瘀瘠癀癠皀皠盀盠眀眠着睠瞀瞠矀矠砀砠础硠碀碠磀磠礀礠祀祠禀禠秀秠稀稠穀穠窀窠竀章笀笠筀筠简箠節篠簀簠籀籠粀粠糀糠紀素絀絠綀綠緀締縀縠繀繠纀纠绀绠缀缠罀罠羀羠翀翠耀耠聀聠肀肠胀胠脀脠腀腠膀膠臀臠舀舠艀艠芀芠苀苠茀茠荀荠莀莠菀菠萀萠葀葠蒀蒠蓀蓠蔀蔠蕀蕠薀薠藀藠蘀蘠虀虠蚀蚠蛀蛠蜀蜠蝀蝠螀螠蟀蟠蠀蠠血衠袀袠裀裠褀褠襀襠覀覠觀觠言訠詀詠誀誠諀諠謀謠譀譠讀讠诀诠谀谠豀豠貀負賀賠贀贠赀赠趀趠跀跠踀踠蹀蹠躀躠軀軠輀輠轀轠辀辠迀迠退造遀遠邀邠郀郠鄀鄠酀酠醀醠釀釠鈀鈠鉀鉠銀銠鋀鋠錀錠鍀鍠鎀鎠鏀鏠鐀鐠鑀鑠钀钠铀铠销锠镀镠門閠闀闠阀阠陀陠隀隠雀雠需霠靀靠鞀鞠韀韠頀頠顀顠颀颠飀飠餀餠饀饠馀馠駀駠騀騠驀驠骀骠髀髠鬀鬠魀魠鮀鮠鯀鯠鰀鰠鱀鱠鲀鲠鳀鳠鴀鴠鵀鵠鶀鶠鷀鷠鸀鸠鹀鹠麀麠黀黠鼀鼠齀齠龀龠ꀀꀠꁀꁠꂀꂠꃀꃠꄀꄠꅀꅠꆀꆠꇀꇠꈀꈠꉀꉠꊀꊠꋀꋠꌀꌠꍀꍠꎀꎠꏀꏠꐀꐠꑀꑠ꒠ꔀꔠꕀꕠꖀꖠꗀꗠꙀꚠꛀ꜀꜠ꝀꞀꡀ"

internal object Base32768SafeEncoding {
    private val encoding = Base32768Encoding(BASE32768_SAFE_ALPHABET)

    fun encode(input: ByteArray): String = encoding.encodeToString(input)

    fun decode(input: String): ByteArray = try {
        encoding.decodeString(input)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("Encrypted rclone filename is not valid base32768", error)
    }
}

private class Base32768Encoding(
    alphabet: String,
) {
    private val encodeA = IntArray(1024)
    private val encodeB = IntArray(4)
    private val decodeMap = IntArray(2048) { BASE32768_INVALID }
    private val splitter: Int

    init {
        val sorted =
            alphabet
                .toCharArray()
                .onEach { char ->
                    require((char.code and 0xFFE0) == char.code) {
                        "Encoding alphabet contains illegal character"
                    }
                }.map(Char::code)
                .sorted()
        require(sorted.size == 1028) { "Encoding alphabet must contain 1028 characters" }
        splitter = sorted[4]
        repeat(1024) { index -> encodeA[index] = sorted[index + 4] }
        repeat(4) { index -> encodeB[index] = sorted[index] }
        encodeA.forEachIndexed { index, code ->
            val bucket = code shr BASE32768_BLOCK_BIT
            require(decodeMap[bucket] == BASE32768_INVALID) { "Encoding alphabet has duplicates" }
            decodeMap[bucket] = index shl BASE32768_BLOCK_BIT
        }
        encodeB.forEachIndexed { index, code ->
            val bucket = code shr BASE32768_BLOCK_BIT
            require(decodeMap[bucket] == BASE32768_INVALID) { "Encoding alphabet has duplicates" }
            decodeMap[bucket] = index shl BASE32768_BLOCK_BIT
        }
    }

    fun encodeToString(src: ByteArray): String {
        val output = CharArray(encodedCharCount(src.size))
        var outputIndex = 0
        var left = 0
        var leftBits = 0
        var index = 0
        while (index < src.size) {
            var chunk = left shl (15 - leftBits)
            chunk = chunk or ((src[index].toInt() and 0xFF) shl (7 - leftBits))
            if (leftBits < 7 && index + 1 < src.size) {
                chunk = chunk or ((src[index + 1].toInt() and 0xFF) ushr (1 + leftBits))
                left = (src[index + 1].toInt() and 0xFF) and ((1 shl (1 + leftBits)) - 1)
                leftBits += 1
                index += 2
            } else {
                chunk = chunk or ((1 shl (7 - leftBits)) - 1)
                left = 0
                leftBits = 0
                index += 1
            }
            output[outputIndex++] = encode15(chunk).toChar()
        }
        if (leftBits > 0) {
            val trailing = (left shl (7 - leftBits)) or ((1 shl (7 - leftBits)) - 1)
            output[outputIndex++] = encode7(trailing).toChar()
        }
        return String(output, 0, outputIndex)
    }

    fun decodeString(input: String): ByteArray {
        val sanitized = input.filterNot { it == '\r' || it == '\n' }
        val output = ByteArray(decodedMaxByteCount(sanitized.length))
        val decoded = decodeChars(output, sanitized.toCharArray())
        return output.copyOf(decoded)
    }

    private fun encode15(source: Int): Int {
        val masked = source and 0x7FFF
        return encodeA[masked shr BASE32768_BLOCK_BIT] or (masked and ((1 shl BASE32768_BLOCK_BIT) - 1))
    }

    private fun encode7(source: Int): Int {
        val masked = source and 0x7F
        return encodeB[masked shr BASE32768_BLOCK_BIT] or (masked and ((1 shl BASE32768_BLOCK_BIT) - 1))
    }

    private fun decodeSymbol(source: Int): DecodedSymbol {
        val trailing = source < splitter
        val base = decodeMap[source shr BASE32768_BLOCK_BIT]
        if (base == BASE32768_INVALID) {
            return DecodedSymbol(0, trailing, success = false)
        }
        return DecodedSymbol(
            value = base or (source and ((1 shl BASE32768_BLOCK_BIT) - 1)),
            trailing = trailing,
            success = true,
        )
    }

    private fun decodeChars(
        output: ByteArray,
        source: CharArray,
    ): Int {
        var outputIndex = 0
        var left = 0
        var leftBits = 0
        source.forEach { char ->
            val decoded = decodeSymbol(char.code)
            require(decoded.success) { "Encrypted rclone filename is not valid base32768" }
            if (decoded.trailing) {
                if (leftBits > 0) {
                    output[outputIndex++] =
                        ((left shl (8 - leftBits)) or (decoded.value ushr (leftBits - 1))).toByte()
                }
                return outputIndex
            }
            if (leftBits > 0) {
                output[outputIndex++] =
                    ((left shl (8 - leftBits)) or (decoded.value ushr (7 + leftBits))).toByte()
                output[outputIndex++] = (decoded.value ushr (leftBits - 1)).toByte()
                left = decoded.value and ((1 shl (leftBits - 1)) - 1)
                leftBits -= 1
            } else {
                output[outputIndex++] = (decoded.value ushr 7).toByte()
                left = decoded.value and 0x7F
                leftBits = 7
            }
        }
        return outputIndex
    }

    private data class DecodedSymbol(
        val value: Int,
        val trailing: Boolean,
        val success: Boolean,
    )
}

private fun encodedCharCount(byteCount: Int): Int = (8 * byteCount + 14) / 15

private fun decodedMaxByteCount(charCount: Int): Int = charCount * 15 / 8
