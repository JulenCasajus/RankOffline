# RankOffline — contrato maestro de paquetes `.pack`

**Estado:** documento histórico de decisiones; parser v2 y herramientas de autor implementados, validación Kotlin integral pendiente  
**Versión de este documento:** 0.1.4  
**Fecha:** 2026-10-31  
**Formato de manifiesto objetivo:** `schemaVersion: 2` (independiente de la versión 0.1.4 de esta especificación)  
**Documento canónico para conservar en el repositorio:** `PACK_MANIFEST_SPEC.md`

> **IMPORTANTE**. Este documento conserva el historial de decisiones y por eso
> mantiene etiquetas antiguas como **[PROPUESTO v2]**. Para un artefacto real,
> prevalecen `PackManifestParser`, sus colaboradores, `CatalogItemFormat`,
> `CatalogGroupFormat`, `PackArchiveReader` y `PackInstaller`. El contrato exacto
> que utilizan las herramientas se resume en `PACK_FORMAT.md` y
> `PACK_GENERATION.md`; una afirmación histórica marcada como pendiente no debe
> usarse para relajar el parser actual.

## 1. Objetivo innegociable [DECIDIDO]

RankOffline es un **motor universal de catálogos descargables**, no una aplicación que conozca de antemano Anime, Manga, Series, Movies o Books.

- El APK **no enumera categorías, paquetes, fuentes, autores, filtros ni agrupaciones concretas**.
- El motor admite descriptores de una fuente confiable, pero v0.2.2 **no incorpora todavía registro remoto ni flujo público de importación local**. Esas vías futuras deben instalar paquetes sin recompilar el APK.
- Cada `.pack` declara sus datos, nombres y traducciones, color, campos, filtros, agrupaciones, portadas, fondos de compartir, **logotipos de proveedores**, atribuciones, licencias y avisos legales. **El APK no incluye marcas, logotipos ni licencias específicos de ningún catálogo o proveedor de datos.**
- La aplicación implementa **un repertorio cerrado de capacidades técnicas seguras** (tipos de dato, widgets, estrategias de agrupación, formatos), pero no enumera *contenidos*. Si un paquete solicita una característica no soportada, se rechaza o se aplica una degradación documentada; **nunca se ejecuta código proporcionado por el paquete**.
- El usuario puede borrar un catálogo sin perder valoraciones, notas, perfiles ni datos personales.
- Las preferencias personales tienen prioridad sobre los valores predeterminados del paquete.

**Prueba de aceptación final:** un APK ya instalado debe poder descubrir e instalar, buscar, filtrar, agrupar, valorar y compartir un `.pack` válido de una categoría desconocida (p. ej. videojuegos) sin ninguna modificación Kotlin.

## 2. Identidad y compatibilidad [DECIDIDO]

| Clave | Uso y regla |
|---|---|
| `schemaVersion` | Versión del **formato de manifiesto**, independiente de la versión del catálogo. La v1 correspondía a la arquitectura anterior; la v0.2.2 implementa un parser de esquema 2 aún pendiente de validación de extremo a extremo. |
| `packId` | **Entero JSON positivo**, único en un registro de distribución y estable en el tiempo. Clave de preferencias, instalación, obras y valoraciones. **No se deriva del nombre visible ni del editor.** El valor `0` está reservado para RankOfflineAll. |
| `version` | Versión publicada del contenido. Su formato exacto y política de comparaciones deben formalizarse. |
| `minRankOfflineVersion` | Versión mínima de la app que entiende el paquete. |
| `id` de cada obra | Identificador estable y único **dentro de su paquete**. Identidad completa: `(pack_id, item_id)`. |
| `id` de grupo | Identidad completa: `(pack_id, group_id)`. |
| `field.id`, `groupType`, `source.id`, `licence.id` | IDs estables dentro de su ámbito; deben evitarse cambios que rompan filtros, grupos, referencias o valoraciones. |

**[DECIDIDO v0.1.3]** `PackId` usa un `Long` de Kotlin con rango **`1..Long.MAX_VALUE`** para paquetes físicos. En `manifest.json`, `packId` es un **número entero JSON, sin comillas**, mayor que cero, sin decimales ni notación exponencial. Por ejemplo, `"packId": 42` es válido; `"packId": "42"`, `"packId": 0` y `"packId": 42.0` no son válidos. El `0` se utiliza exclusivamente como identificador virtual de RankOfflineAll, incluida la persistencia del último ámbito y el backup completo. Los nombres y traducciones de paquetes pueden repetirse libremente. **No usar `MediaType`, `OfficialPackIds` ni tablas de categorías oficiales en el motor nuevo.**

Las claves de obras y grupos siguen siendo compuestas `(pack_id, item_id/group_id)`; `item_id` y `group_id` permanecen como cadenas locales del paquete. El almacenamiento Room usa `INTEGER` para `pack_id` (no `TEXT`); los backups, los resúmenes JSON y el campo numérico de selección de ámbito utilizan el mismo valor. El directorio físico instalado es `files/packs/pack-<id>/` (por ejemplo, `files/packs/pack-42/`). Se genera mediante `PackId.directoryName()` y no depende de `displayName`. Los recursos descargados y las portadas del paquete quedan bajo ese directorio. El prefijo `pack-` solo forma parte del nombre de carpeta: `PackId` sigue siendo un `Long` y el manifiesto sigue declarando un número. Los datos personales se conservan por separado bajo `files/local/<id>/` (por ejemplo, `files/local/42/`) para que sobrevivan a la desinstalación. Nunca se crea `files/packs/pack-0/` porque RankOfflineAll es virtual.

**Cambio de formato sin migración:** esta versión no lee automáticamente instalaciones, bases Room, preferencias o backups anteriores con `packId` textual. Se mantiene la decisión de realizar una instalación limpia de la v0.2.2; la migración se abordará en una fase posterior.

**Nota de interoperabilidad:** en servicios futuros basados en JavaScript, los enteros superiores a `9_007_199_254_740_991` no se representan con precisión como `Number`. Si el registro remoto usa JavaScript, deberá emplear un lector de enteros exacto o acotar la asignación de IDs a ese máximo. Esta observación no altera el rango `Long` admitido por el código Kotlin.

**Ejemplo de identidad en `manifest.json`** (fragmento; los demás campos obligatorios se omiten aquí):

```json
{
  "schemaVersion": 2,
  "packId": 42,
  "displayName": "Una colección de ejemplo",
  "version": "1.0.0",
  "minRankOfflineVersion": "0.2.2"
}
```

Los valores numéricos 1–5 reservados por el proyecto para sus paquetes iniciales son datos del registro/publicador, **nunca condiciones por categoría en el código del motor**. Dos paquetes pueden compartir `displayName`, pero no compartir el mismo `packId` dentro de un registro.

**[PENDIENTE]** Un número elimina el conflicto de nombres, pero **no elimina la colisión de identidades**: el registro debe asignar/validar números únicos, impedir apropiación de un ID ajeno y definir propietarios, firmas o autenticación. La app no asigna IDs según el nombre ni incrusta correspondencias de categorías oficiales. Un nombre de editor aportado por el propio `.pack` no demuestra autoría.

## 3. Estructura de un `.pack` [EXISTENTE v1 + PROPUESTO v2]

El archivo `.pack` se trata como un contenedor de datos; el instalador valida cada ruta y su integridad antes de activar el paquete:

```text
sample-games.pack
├── manifest.json
├── catalog/
│   └── items.jsonl               # FORMATO DE REGISTROS AÚN POR CERRAR
├── groups/
│   └── franchises.jsonl          # opcional
├── covers/
│   └── ...                       # recursos de portada opcionales
└── metadata/
    ├── share.webp                # fondo opcional de tarjetas Share
    ├── cover_index.json          # si el paquete incluye portadas locales
    ├── attribution/
    │   └── source-logo.png       # imagen de marca de un proveedor, si corresponde
    └── legal/
        ├── DATA_LICENCE.txt
        └── THIRD_PARTY_NOTICES.txt
```

**[EXISTENTE v1]** Se permiten datos bajo `catalog/`, `groups/`, `covers/` y `metadata/`. Se prohíben rutas absolutas, segmentos `..`, separadores `\\` y payloads ejecutables (`.dex`, `.jar`, `.class`, `.so`, `.apk`). Estas reglas serán la base, no la protección completa.

**[PROPUESTO v2]** Los nombres exactos y el esquema de las líneas de `catalog/`/`groups/` siguen pendientes. El ejemplo `.jsonl` **no implica que ese formato esté ya soportado**. Los archivos legales están bajo `metadata/legal/`; los logotipos y otras imágenes de atribución del paquete se ubicarán bajo `metadata/attribution/` (propuesta v2).

## 4. Campos del manifiesto

### 4.1 Identidad, información visible y traducciones

| Campo v2 propuesto | Propósito | Estado |
|---|---|---|
| `schemaVersion` | Compatible con parser versión 2 | [PROPUESTO v2] |
| `packId`, `version`, `minRankOfflineVersion` | `packId` es ahora entero JSON positivo; versión y compatibilidad permanecen independientes | [DECIDIDO v0.1.3] |
| `displayName` | Nombre de reserva legible, sin depender de recursos `R.string` | [EXISTENTE v1] |
| `presentation.names` | Nombres localizados por etiquetas de idioma (p. ej. `es`, `en`, `eu`) | [PROPUESTO v2] |
| `presentation.accentColor` | Color `#RRGGBB` o formato definido de manera estricta | [PROPUESTO v2] |
| `presentation.shareBackgrounds` | Imágenes opcionales para tarjetas `topSeven` y `topTen` | [PROPUESTO v2] |

La interfaz utiliza la traducción del paquete si existe y un nombre de reserva cuando no. **Los idiomas disponibles para la interfaz de la app** (`Localization.kt`) son independientes de los idiomas admitidos en cada catálogo.

**[DECIDIDO v0.1.3 / v0.2.2]** `presentation.shareBackgrounds` declara dos rutas opcionales e independientes: `topSeven` (vertical 1080×1920) y `topTen` (horizontal 1920×1080). PNG/JPEG/WebP, bajo `metadata/`, declaradas en `integrity.files` y con un límite de **8 MiB por archivo**. La ausencia de una imagen concreta utiliza exclusivamente el fondo genérico correspondiente de la app, sin tomar la imagen de la otra plantilla. RankOfflineAll utiliza siempre los dos fondos genéricos. Ejemplo:

```json
"presentation": {
  "names": {"es": "Juegos de ejemplo"},
  "accentColor": "#4389C6",
  "shareBackgrounds": {
    "topSeven": "metadata/top7.webp",
    "topTen": "metadata/top10.webp"
  }
}
```

Ambas propiedades son opcionales; si no existe el archivo, **no debe referenciarse** desde el manifiesto. Las rutas declaradas deben pasar validación de integridad.

### 4.2 Campos y filtros completamente dinámicos [PROPUESTO v2]

**`fields`** declara el esquema de atributos que las obras y, cuando proceda, los grupos pueden almacenar. Cada campo debe declarar:

- `id`: identificador estable (`genre`, `platform`, `author`, `release_year`, etc.; **son ejemplos, nunca nombres reservados**).
- `type`: tipo entre los soportados por el motor (propuesta inicial: `text`, `integer`, `decimal`, `boolean`, `date`, `choice`).
- `multiple`: si acepta varios valores; `false` para valor único.
- `labels`: traducciones opcionales; debe existir un fallback.
- Opcionalmente, `options` para enumeraciones y reglas de validación para rangos.

**`filters`** lista componentes de filtrado genéricos. Cada filtro referencia `field` definido en `fields`, tiene un `id`, `type` de interfaz admitido (p. ej. `multi_select`, `range`) y `order`. `labels` define cómo se llama. El motor interpreta esas declaraciones, **no comprueba si `field == "genre"`, `"author"` o `"studio"`**.

**`subtitle`** contiene IDs de campos que la interfaz puede mostrar junto al título. **[CAMBIO PROPUESTO]** No exigir que un campo de subtítulo también sea un filtro, como hace el parser v1: debe bastar con que esté definido en `fields`.

En Room, los valores van en tablas genéricas de atributos por `(pack_id, media_id, field_id, position)`, con texto/número/booleano, mientras que el manifiesto define qué significan y qué tipos se esperan. La validación del importador debe impedir valores incompatibles o repetidos ilícitos.

### 4.3 Capacidades y valores predeterminados [EXISTENTE v1]

```json
"capabilities": {
  "search": true,
  "filters": true,
  "covers": true,
  "popularity": true,
  "merge": true,
  "ratings": true
},
"defaults": {
  "showImages": true,
  "stickyScoreHeader": true,
  "mergeEnabled": false,
  "ratingMode": "elaborated",
  "oneCoverPerGroup": false
}
```

Los flags existentes pueden mantenerse como contratos declarativos y revisarse si aparecen nuevas capacidades. La app decide **cómo** implementar cada capacidad; el paquete no incluye código de widgets ni pantallas.

**[PENDIENTE]** Definir si el paquete aporta una sugerencia de criterios/perfiles de valoración y cómo se combinan con los perfiles personales del usuario, que nunca deben perderse al actualizar un paquete.

### 4.4 Grupos y merge [EXISTENTE v1 + PROPUESTO v2]

El parser v1 reconoce `merge.enabled`, `merge.strategy` y `merge.groupType`; estrategias: `NONE`, `GROUP_ID`, `COLLECTION_ID`, `RELATION_GRAPH`. La propiedad opcional `merge.labels` puede conservar metadatos descriptivos sobre la agrupación; **el botón de la aplicación debe mostrar siempre `Merge`**, independientemente del tipo de paquete.

Las agrupaciones pertenecen al paquete y usan IDs estables. Una obra puede pertenecer a un grupo mediante una tabla de miembros. El representante de un grupo se guarda en una **relación independiente** para que eliminar una obra no borre el grupo; el número de miembros se calcula consultando esa relación de membresía, no mediante un contador persistido.

### 4.5 Proveedores, referencias de portadas y redes

**[EXISTENTE v1]** `coverSources` incluye `id`, `providerId`, `urlTemplates`, `allowedHosts` y campos de atribución. Las URLs de portada son plantillas HTTPS con variables controladas (`{reference}` y `{providerId}`), y hosts permitidos.

**[PROPUESTO v2]** Los `coverSources` apuntarán con `sourceId` a una fuente declarada en `attribution.sources`, evitando repetir el nombre o enlace legal en dos lugares. La descarga de portadas debe someterse a límites, validación del destino y coordinación de red; los metadatos remotos **nunca permiten ejecutar peticiones arbitrarias**.

**[PENDIENTE]** Política definitiva de redirecciones, IP privadas/DNS rebinding, tamaños, tipos MIME, número de imágenes, caché y renovación de fuentes. Descargar o mostrar una portada no significa que el APK pueda atribuirse su licencia.

### 4.6 Atribuciones, licencias y avisos [PROPUESTO v2; parser preliminar ya redactado]

**`attribution`** se define en el borrador de `PackAttributions.kt`, con la siguiente estructura:

- `publisher`: `name`, `url` opcional.
- `sources[]`: `{id, name, url?, notices[]}`; identificadores únicos por paquete.
- `licences[]`: `{id, name, url?, textPath?}`; `textPath` puede señalar un documento legal local.
- `datasets[]`: `{name, sourceIds[], licenceIds[], notices[]}`; las referencias deben existir.
- `notices[]`: avisos globales del paquete.
- `legalFiles[]`: rutas adicionales de documentos legales.

**Reglas del parser ya preparado (todavía sin integración completa en `PackManifestParser`):**

- URLs externas únicamente HTTPS con sintaxis y host admisibles; mostrarlas al usuario y abrirlas solo por acción explícita. Una URL válida no acredita que el remitente sea fiable.
- Documentos en `metadata/legal/*.txt` declarados en `integrity.files`, con longitud máxima de **512 KiB por documento**.
- Límites de cantidad y longitud de entradas; referencias a fuente y licencia verificadas; IDs únicos.
- Nombres, textos y avisos se renderizan como **texto sin HTML ni scripts**.
- Avisos específicos de proveedores (por ejemplo, un proveedor que exija un texto exacto) vienen en el paquete, no en `AttributionRegistry.kt` ni en una rama por categoría.
- Las obligaciones legales del propio APK y de sus bibliotecas/recursos continúan fuera de los packs, en información legal de RankOffline.

### 4.6.1 Separación estricta entre recursos del APK y recursos de los `.pack` [DECIDIDO]

- **APK / aplicación:** contiene el código del motor, la marca e iconos propios de RankOffline, las traducciones de la interfaz, su licencia y los avisos que correspondan a **bibliotecas y recursos de terceros efectivamente incluidos en el APK**. Aunque queramos que el menú legal de la aplicación sea mínimo, estas obligaciones **no se pueden trasladar ficticiamente a paquetes que el usuario quizá nunca instale**.
- **Paquete descargable:** contiene todas las marcas, logotipos, textos legales, licencias de datasets, atribuciones de fuentes y materiales derivados **específicos del paquete**. Ni los logotipos de sus proveedores ni sus enlaces deben estar precargados en `res/drawable`, `assets` o constantes Kotlin del APK.
- **Ejemplo TMDB:** si un `.pack` utiliza datos/imágenes de TMDB, el propio paquete debe aportar el logotipo oficial aprobado y el aviso exigido por el proveedor, además de la fuente/enlace. La pantalla de créditos muestra estos datos **desde el manifiesto del paquete**, sin ninguna condición `if (provider == "tmdb")` ni cadenas o imágenes específicas en Kotlin. Las obligaciones exactas se comprobarán contra los términos aplicables al publicarlo.
- **Sin paquetes instalados:** la app no debe mostrar TMDB, AniList, MangaDex ni otros proveedores de catálogos; únicamente información legal propia del APK y sus dependencias realmente incluidas.
- **Sin garantía jurídica automática:** pasar la validación técnica de `attribution` no demuestra licencias, derechos de marca, permisos ni exactitud de las atribuciones; el publicador y el proceso de revisión deben comprobarlos.

### 4.6.2 Imágenes de atribución declaradas por el manifiesto [PROPUESTO v2]

Ampliar `attribution.sources[]` con un campo **opcional** `logoPath`, que referencia una imagen de atribución **dentro del mismo `.pack`**. Que sea opcional en el esquema no significa que el publicador pueda omitir un logo cuando la licencia de su proveedor lo exija.

```json
"attribution": {
  "publisher": { "name": "Example Publisher", "url": "https://example.org" },
  "sources": [{
    "id": "example_source",
    "name": "Example Source",
    "url": "https://example.org",
    "logoPath": "metadata/attribution/source-logo.png",
    "notices": ["Example attribution notice required by the source"]
  }],
  "licences": [],
  "datasets": [{
    "name": "Example Records",
    "sourceIds": ["example_source"],
    "licenceIds": [],
    "notices": []
  }],
  "notices": [],
  "legalFiles": []
}
```

Requisitos previstos para `logoPath`:

1. Ruta normalizada bajo `metadata/attribution/`, declarada en `integrity.files` con tamaño y SHA-256 correctos.
2. Permitir únicamente PNG, JPEG o WebP inertes, tras validar su contenido real, límites de dimensiones, píxeles y tamaño descomprimido; **no cargar SVG activo, HTML, scripts ni imágenes remotas directamente desde el manifiesto**.
3. No transformar, recortar ni recolorear marcas de proveedores salvo permiso explícito; preservar proporción y visibilidad en la pantalla de créditos.
4. Mostrar logotipo, enlace y aviso en la sección de **fuentes del paquete** dentro de About/Credits, solo cuando corresponde a un catálogo instalado o a material que legalmente exige mantener atribución.
5. Cuando `logoPath` no exista, mostrar el nombre y los avisos en texto; si ese proveedor exige logotipo, el proceso de publicación debe rechazar el paquete incompleto.

**Estado de implementación:** `PackAttributions.kt` redactado anteriormente **aún no acepta `logoPath`**. Es necesario añadirlo a `AttributionSource`, `PackAttributionParser` y los validadores del instalador; este documento define la ampliación, no afirma que esté implementada.

### 4.6.3 Desinstalación y atribuciones [DECIDIDO para comportamiento; detalles PENDIENTES]

Al desinstalar un `.pack`, eliminar sus logotipos, fondos, documentos y **cachés de imágenes gestionadas por el paquete** que dejen de necesitarse. Las valoraciones, notas y otros datos personales sobreviven. Si se conserva algún material protegido procedente del catálogo (por ejemplo, una portada que el usuario haya elegido conservar), la aplicación deberá conservar también la atribución aplicable **o** evitar conservar ese material. No eliminar documentación legal necesaria mientras ese contenido siga disponible.

**[PENDIENTE]** Definir exactamente la política de conservación de imágenes de usuario y atribuciones después de desinstalar/actualizar el paquete; verificar condiciones de las fuentes.

### 4.7 Lista de archivos e integridad [EXISTENTE v1]

```json
"integrity": {
  "installedBytes": 1234,
  "files": [
    {"path":"catalog/items.jsonl","bytes":1234,"sha256":"<64 hexadecimal digits>"}
  ]
}
```

**Cada archivo instalado** debe estar declarado con ruta relativa, tamaño exacto y SHA-256; `installedBytes` coincide con la suma de tamaños declarados. La verificación se realiza **antes de activar el paquete**. Los documentos legales, **logotipos de proveedores** y fondos visuales también deben incluirse.

Esto confirma integridad **con respecto al manifiesto recibido**; no demuestra que el publicador sea quien dice ser. Para distribución comunitaria serán necesarias garantías adicionales (identidad editorial/registro, firma o huella verificada, política de actualización y revocación).

El instalador tiene que proteger contra path traversal, symlinks, zip bombs, archivos duplicados, formatos/bytes desmesurados, errores parciales, cancelaciones y actualizaciones interrumpidas. La instalación debe ser recuperable/transaccional.

## 5. Formato de datos de las obras [PROPUESTO v2; MODELO KOTLIN REDACTADO, INTEGRACIÓN PENDIENTE]

El formato propuesto para `catalog/` es **JSONL UTF-8: una obra por línea**. Este formato es una decisión de implementación propuesta y debe validarse aún con el instalador, el empaquetador y los tests de migración. `MEDIA_CATALOG_FORMAT_VERSION = 2` indica la versión del **registro**, independiente de `PackManifest.schemaVersion = 2`.

Ejemplo de línea JSONL (aquí formateado en varias líneas para legibilidad):

```json
{
  "id": "game-123",
  "title": "Juego de ejemplo",
  "provider": "example_data",
  "providerId": "external-123",
  "popularity": 96.5,
  "popularityRank": 42,
  "remoteCoverRef": "external-123",
  "attributes": {
    "genre": ["metroidvania", "adventure"],
    "platform": ["pc", "switch"],
    "year": 2025,
    "developer": "Estudio de ejemplo"
  },
  "aliases": [{"title": "Example Game", "language": "en", "kind": "localized"}]
}
```

- `packId` **no se repite en cada registro**. El importador obtiene el `PackId` del manifiesto validado, con el que construye claves compuestas `(pack_id, id)` para obras, atributos y alias.
- `mediaType` **no existe**. No hay catálogo ni prefijos de ID obligatorios según Anime, Manga, etc.
- `provider` y `providerId` son **opcionales** y describen procedencia, nunca la identidad primaria; `providerId` requiere un `provider`. Atribuciones, documentos y enlaces del proveedor se declaran en `attribution` del manifiesto.
- Las claves de `attributes` deben existir en `fields` del manifiesto. Tipos admitidos: `text`, `integer`, `decimal`, `boolean`, `date` (cadena `YYYY-MM-DD` válida) y `choice`. Los campos `multiple: true` usan arrays JSON. Un campo ausente no se representa con `null`.
- El manifiesto debe validar estructura, IDs, cardinalidad, rangos y opciones declaradas. El importador rechaza atributos desconocidos, tipos erróneos, duplicados o datos desmesurados. `integer` se limita al rango de enteros representables exactamente por `Double` mientras Room almacene `value_number` como `Double?`.
- `aliases` sustituye la necesidad de columnas como `originalTitle`; los restantes metadatos de contenido (año, formato, autor, estudio, género, etc.) son **atributos definidos por el paquete**.
- La integridad SHA-256, tamaño y versión del catálogo se declaran en el **manifiesto principal del `.pack`**, no en un `MediaCatalogManifest` duplicado.
- `remoteCoverRef` es una referencia opaca, **no una URL arbitraria que se deba visitar**. **PENDIENTE:** definir cómo identifica cada obra su `coverSourceId` cuando el paquete declara varias fuentes de portadas, y adaptar Room si hace falta.
- **NO IMPLEMENTADO END-TO-END:** el `MediaCatalogFormat.kt` v2 ha sido redactado, pero `PackArchitecture.kt`, los importadores, DAOs, la herramienta de construcción de packs y las pruebas siguen sin migrarse.

## 6. Registro remoto de paquetes [DECIDIDO como arquitectura; contrato PENDIENTE]

El APK no contiene una lista compilada de paquetes. Un futuro **registro confiable**, todavía no conectado en v0.2.2, deberá publicar como mínimo `packId`, versión, nombre/idiomas, color, tamaño, compatibilidad, editor, URL de descarga e integridad/identidad editorial. Así se podrán explorar paquetes antes de instalarlos.

Los cinco paquetes oficiales iniciales serán entradas normales del registro. Un paquete local importado también se registra sin depender de una conexión. El estado de instalado, las versiones y las preferencias se conservan localmente.

**PENDIENTE:** contrato JSON exacto del registro, versiones, autenticidad, moderación, pagos o permisos si algún día se contemplan, gestión de editores, colisiones de IDs, búsqueda, actualizaciones, revocaciones y caché offline.

## 7. Datos personales y migración [DECIDIDO]

- Elementos y grupos se identifican por **claves compuestas con `pack_id`**; nunca por `id` global únicamente.
- Las valoraciones personales y sus puntuaciones de criterios **no tienen clave foránea a datos instalados**: borrar el catálogo no las borra.
- Las identidades deben seguir siendo estables entre actualizaciones; cambios de ID requieren una migración explícita y verificable.
- Los backups JSON deben guardar `pack_id` y datos de usuario sin exigir que el paquete esté instalado.
- Preferencias (orden, filtros, presentación, paletas y perfiles, según aplique) se resuelven por `PackId` y el usuario prevalece sobre los defaults del manifiesto.
- Antes de borrar la compatibilidad antigua, diseñar y probar migración desde los cinco `MediaType` y los datos personales existentes. **No usar migraciones destructivas.**

## 8. Comportamientos inválidos que deben rechazarse [DECIDIDO]

- Un manifiesto intenta cargar código, scripts, bibliotecas nativas o contenido activo.
- `schemaVersion` desconocida sin contrato de compatibilidad.
- Dos archivos usan la misma ruta; ruta sale del directorio del paquete.
- Falta un archivo declarado o su SHA-256/tamaño no coincide.
- `packId`, IDs de obras/grupos/filtros/fuentes/licencias inválidos o duplicados.
- Un filtro referencia un campo inexistente o solicita un tipo/widget incompatible.
- Una obra o grupo tiene atributos que violan el esquema declarado.
- Un grupo enlaza una obra de otro paquete.
- Una licencia/dataset referencia una fuente o documento legal inexistente.
- Un `attribution.sources[].logoPath` referencia una imagen ausente, no declarada, fuera de `metadata/attribution/`, de tipo inválido o que supera los límites de recursos.
- Un URL intenta usar `http`, `file`, `javascript`, credenciales embebidas o un destino no autorizado.
- Datos desmesurados, imágenes no válidas o recursos demasiado pesados.

## 9. Registro de decisiones y asuntos abiertos

| Nº | Decisión / pregunta | Estado | Nota |
|---|---|---|---|
| D-01 | El APK no conoce las categorías ni enumera los packs | **DECIDIDO** | Eliminar `MediaType` y `OfficialPackIds` del motor. |
| D-02 | Identidad por `(pack_id, item_id/group_id)` | **DECIDIDO** | Entidades Room rediseñadas. |
| D-03 | Filtros y títulos definidos por el `.pack` | **DECIDIDO** | Contrato `fields`/`filters` aún propuesto. |
| D-04 | Colores, traducciones y agrupación declarados | **DECIDIDO** | Definir fallbacks de idioma. |
| D-05 | Atribuciones y documentos legales en el `.pack` | **DECIDIDO** | `PackAttributions.kt` es borrador implementado, pendiente integrar. |
| D-06 | Fondos Share proporcionados por el `.pack` | **DECIDIDO** | v1 uno por pack; v2 posible Top 7/Top 10. |
| D-07 | Las valoraciones sobreviven a desinstalar el pack | **DECIDIDO** | Sin FK desde datos personales a catálogo. |
| D-08 | Registros de packs remotos y locales | **DECIDIDO** | API JSON de registro pendiente. |
| D-09 | Cero logos, enlaces y licencias específicos de proveedores de catálogos dentro del APK | **DECIDIDO** | Cargar fuentes, branding y avisos desde el `.pack`; eliminar recursos TMDB y equivalentes del APK. |
| D-10 | Licencias/avisos de dependencias incorporadas al APK se conservan en el APK | **DECIDIDO** | Solo se trasladan a los `.pack` las obligaciones realmente asociadas a su contenido. |
| D-11 | Permitir logotipo local opcional por fuente | **PROPUESTO v2** | `attribution.sources[].logoPath`; integridad, formato seguro y presentación de créditos. |
| P-01 | Sintaxis final, nombres y tipos de campos v2 | **PENDIENTE** | No mezclar filtro/widget con tipo de dato. |
| P-02 | Formato concreto de registros `catalog/` y `groups/` | **PROPUESTO (catalog/items.jsonl)** | Modelo JSONL de obras redactado; grupos e integración aún pendientes. |
| P-03 | Asignación global de IDs numéricos y firma/editor | **PARCIALMENTE DECIDIDO** | `packId` numérico positivo decidido; asignación única, suplantación y autorizaciones pendientes. |
| P-04 | Contrato de criterios y perfiles de valoración | **PENDIENTE** | Preservar personalización y backup. |
| P-05 | Autenticidad de paquetes, URLs y límites de recursos | **PENDIENTE** | Validación técnica ≠ confianza jurídica. |
| P-06 | v1→v2 y migración Room/JSON | **PENDIENTE** | Probar con datos reales sin pérdida. |
| P-07 | Fondos Share individuales o comunes | **DECIDIDO** | Dos fondos opcionales por pack (Top 7 vertical y Top 10 horizontal); fallback individual predeterminado. |
| P-08 | Reglas de compatibilidad ante campos desconocidos | **PENDIENTE** | Rechazar o degradar de forma documentada. |
| P-09 | Política de retención y atribución de portadas conservadas sin `.pack` | **PENDIENTE** | Conservar atribución si se mantiene material protegido; no perder datos personales. |
| P-10 | Reglas de publicación para proveedores que exigen logo/avisos específicos | **PENDIENTE** | No deducir cumplimiento legal solo de la presencia de `logoPath`. |
| P-11 | Relación de `remoteCoverRef` con múltiples `coverSources` | **PENDIENTE** | Definir `coverSourceId` por registro y persistencia si corresponde. |

## 10. Plan de integración por componentes

1. **Contrato**: cerrar `manifest.v2.example.json`, formato de items/grupos y JSON Schema antes de editar indiscriminadamente el parser.
2. **`PackArchitecture.kt`**: parser versionado, `PackManifest`, presentación, `fields`, `attribution`, `attribution.sources[].logoPath`, fondos y validadores de recursos.
3. **Importadores y Room**: transformar registros genéricos en `MediaItemEntity`/`MediaItemAttributeEntity`, grupos y enlaces; claves compuestas.
4. **`PackRegistry` y descubrimiento**: combinar packs instalados, importados y publicados remotamente; sin `OfficialPackIds`.
5. **Repositorios/ViewModel**: listas, búsqueda, ordenación, filtros y rating por `PackId`.
6. **UI**: `RankOfflineSelector`, Search, My Ranking, Settings, Share y About generan controles a partir del manifiesto. About/Credits muestra los logos y avisos del paquete dinámicamente; sin proveedores conocidos en Kotlin.
7. **Editor/backup**: preservar criterios y datos personales al eliminar/actualizar catálogo.
8. **Limpieza APK**: eliminar `AttributionRegistry`, `R.drawable.tmdb_primary_short_blue` y demás recursos/URLs/notices de proveedores integrados en `SettingsScreens.kt`, `MediaCoverProviders.kt` u otros componentes; los hosts de portadas proceden del manifiesto validado. Conservar únicamente los avisos legalmente necesarios del motor y de las dependencias incorporadas al APK.
9. **Pruebas de aceptación**: paquete totalmente desconocido + paquetes existentes migrados + contenidos maliciosos/rotos + recuperación tras fallos; comprobar que ningún paquete instalado ⇒ ningún logo/aviso de catálogo mostrado y que un pack nuevo puede introducir un proveedor/logotipo desconocido.

## 11. Ejemplo completo y mantenimiento

Un futuro conjunto de fixtures deberá mantener `manifest.v2.example.json` como
**propuesta ilustrativa**, alineada con el parser v0.2.2 (`SCHEMA_VERSION = 2`)
y con `packId` entero positivo. No se publica ahora un ejemplo no validado por
una prueba integral; cuando exista, cada cambio deberá actualizar ambos archivos.

### Plantilla de registro de cambios

```text
Fecha: AAAA-MM-DD
Versión del documento: X.Y.Z
Cambio: ...
Motivo: ...
Campos afectados: ...
Estado: DECIDIDO / PROPUESTO / PENDIENTE
Compatibilidad/migración: ...
Pruebas necesarias: ...
```

### Historial

- **2026-10-31 · 0.1.4:** los directorios físicos de paquetes siguen la convención `files/packs/pack-<id>/`; el motor genera y valida ese nombre desde el `PackId` numérico. El almacenamiento de datos personales bajo `files/local/<id>/` permanece separado y no se migra. No cambia `schemaVersion: 2`.

- **2026-10-31 · 0.1.3:** `packId` pasa de cadena a entero JSON positivo representado como `Long`; el `0` queda reservado para RankOfflineAll. Room, backups, preferencias y nombres de carpetas deben seguir el mismo contrato. Se fijan dos fondos Share opcionales e independientes, Top 7 vertical y Top 10 horizontal; los nombres de paquete no son identidades. Sin migraciones ni codificación de los IDs oficiales en el motor.

- **2026-10-28 · 0.1.2:** se propone `MediaCatalogFormat.kt` v2 con registros JSONL de obras, atributos tipados y alias, sin `MediaType` ni segundo manifiesto de catálogo; se documenta la conversión a Room y la decisión pendiente de varias fuentes de portada.
- **2026-10-28 · 0.1.1:** se confirma que todo branding, logos, enlaces y atribuciones de proveedores de catálogos pertenece a los `.pack` (incluido TMDB); se propone `attribution.sources[].logoPath`, se documenta la limpieza pendiente del APK y se preservan las obligaciones de bibliotecas realmente incorporadas a la app.
- **2026-10-28 · 0.1.0:** se consolida el objetivo de motor dinámico, modelo por `PackId`, campos/filtros, presentación, Share, integridad, atribuciones, licencias, registro remoto, seguridad y preguntas abiertas. Basado en `PackArchitecture.kt` v1, nuevo `MediaDatabaseEntities.kt` y el borrador `PackAttributions.kt`.
