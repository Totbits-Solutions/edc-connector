# EDC Connector

[![documentation](https://img.shields.io/badge/documentation-8A2BE2?style=flat-square)](https://eclipse-edc.github.io)
[![discord](https://img.shields.io/badge/discord-chat-brightgreen.svg?style=flat-square&logo=discord)](https://discord.gg/n4sD9qtjMQ)
[![latest version](https://img.shields.io/maven-central/v/org.eclipse.edc/boot?logo=apache-maven&style=flat-square&label=latest%20version)](https://search.maven.org/artifact/org.eclipse.edc/boot)
[![license](https://img.shields.io/github/license/eclipse-edc/Connector?style=flat-square&logo=apache)](https://www.apache.org/licenses/LICENSE-2.0)
<br>
[![build](https://img.shields.io/github/actions/workflow/status/eclipse-edc/Connector/verify.yaml?branch=main&logo=GitHub&style=flat-square&label=ci)](https://github.com/eclipse-edc/Connector/actions/workflows/verify.yaml?query=branch%3Amain)
[![snapshot build](https://img.shields.io/github/actions/workflow/status/eclipse-edc/Connector/trigger_snapshot.yml?branch=main&logo=GitHub&style=flat-square&label=snapshot-build)](https://github.com/eclipse-edc/Connector/actions/workflows/trigger_snapshot.yml)
[![nightly build](https://img.shields.io/github/actions/workflow/status/eclipse-edc/Connector/nightly.yml?branch=main&logo=GitHub&style=flat-square&label=nightly-build)](https://github.com/eclipse-edc/Connector/actions/workflows/nightly.yml)

---

## Documentation

Base documentation can be found on the [documentation website](https://eclipse-edc.github.io). \
Developer documentation can be found under [docs/developer](docs/developer), \
where the main concepts and decisions are captured as [decision records](docs/developer/decision-records/README.md).

## Directory structure

### `spi`

This is the primary extension point for the connector. It contains all necessary interfaces that need to be implemented
as well as essential model classes and enums. Basically, the `spi` modules defines the extent to what users can
customize and extend the code.

### `core`

Contains all absolutely essential building that is necessary to run a connector such as `TransferProcessManager`,
`ProvisionManager`, `DataFlowManager`, various model classes, the protocol engine and the policy piece. While it is
possible to build a connector with just the code from the `core` module, it will have very limited capabilities to
communicate and to interact with a data space.

### `extensions`

This contains code that extends the connector's core functionality with technology- or cloud-provider-specific code. For
example a transfer process store based on Azure CosmosDB, a secure vault based on Azure KeyVault, etc. This is where
technology- and cloud-specific implementations should go.

If someone were to create a configuration service based on Postgres, then the implementation should go into
the `extensions/database/configuration-postgres` module.

### `data-protocols`

Contains implementations for communication protocols a connector might use, such as DSP.

---

## Casos de Uso: Evaluacion de Politicas

En la carpeta [docs/totbits](docs/totbits) se documenta como el EDC Connector evalua politicas en los 4 momentos
clave del ciclo de vida de un intercambio de datos. El motor de politicas es propio del EDC (basado en ODRL),
no utiliza Open Policy Agent.

### Que se demuestra

El EDC evalua politicas en 4 scopes distintos, cada uno con un proposito diferente:

| Caso | Scope | Pregunta que responde | Documento |
|------|-------|-----------------------|-----------|
| 1 | `catalog.request` | Puede este consumidor **ver** el asset? | [02_CASO_DE_USO_CATALOG_REQUEST.md](docs/totbits/02_CASO_DE_USO_CATALOG_REQUEST.md) |
| 2 | `contract.negotiation` | Puede **acordar** condiciones para usarlo? | [03_CASO_DE_USO_CONTRACT_NEGOTIATION.md](docs/totbits/03_CASO_DE_USO_CONTRACT_NEGOTIATION.md) |
| 3 | `transfer.process` | Puede **obtener** los datos ahora? | [04_CASO_DE_USO_TRANSFER_PROCESS.md](docs/totbits/04_CASO_DE_USO_TRANSFER_PROCESS.md) |
| 4 | `policy.monitor` | **Sigue** pudiendo usarlos? | [05_CASO_DE_USO_POLICY_MONITOR.md](docs/totbits/05_CASO_DE_USO_POLICY_MONITOR.md) |

Documentacion adicional:
- [00_INDICE_CASOS_DE_USO.md](docs/totbits/00_INDICE_CASOS_DE_USO.md) — Indice general con la vision completa
- [01_POLITICAS_IDENTIDAD_ARQUITECTURA.md](docs/totbits/01_POLITICAS_IDENTIDAD_ARQUITECTURA.md) — Arquitectura del motor de politicas e Identity Hub

Cada documento incluye:
- Explicacion del flujo interno con el codigo fuente relevante
- Peticiones HTTP completas con JSON (listos para ejecutar)
- Escenarios de exito y fallo con el resultado esperado

### Arrancar los conectores para pruebas

Para ejecutar los casos de uso se necesitan **dos instancias** del EDC Connector (proveedor y consumidor).
Se usa Gradle para arrancar cada instancia con la tarea `run` del plugin `application`.

#### Requisitos previos

- Java 17+
- Gradle (se usa el wrapper incluido `./gradlew`)

#### Paso 1: Compilar el proyecto

```bash
./gradlew build -x test
```

#### Paso 2: Crear ficheros de configuracion

> Los ficheros `provider.properties` y `consumer.properties` ya estan incluidos en la raiz del repositorio.
> Si no los tienes, crealos con el contenido indicado abajo.

**Proveedor** (`provider.properties`):

```properties
edc.participant.id=provider
web.http.port=18080
web.http.path=/api
web.http.management.port=18181
web.http.management.path=/api/management
web.http.control.port=18183
web.http.control.path=/api/control
web.http.protocol.port=18282
web.http.protocol.path=/api/dsp
edc.transfer.send.retry.limit=3
edc.transfer.send.retry.base-delay.ms=500
edc.negotiation.provider.send.retry.limit=3
edc.negotiation.provider.send.retry.base-delay.ms=500
```

**Consumidor** (`consumer.properties`):

```properties
edc.participant.id=consumer
web.http.port=28080
web.http.path=/api
web.http.management.port=28181
web.http.management.path=/api/management
web.http.control.port=28183
web.http.control.path=/api/control
web.http.protocol.port=28282
web.http.protocol.path=/api/dsp
edc.transfer.send.retry.limit=3
edc.transfer.send.retry.base-delay.ms=500
edc.negotiation.consumer.send.retry.limit=3
edc.negotiation.consumer.send.retry.base-delay.ms=500
```

#### Paso 3: Arrancar los conectores

Abrir **dos terminales** en la raiz del proyecto y ejecutar un comando en cada una.
Cada comando compila lo necesario y arranca el conector; la terminal queda ocupada mostrando los logs.

**Terminal 1 -- Proveedor:**

```bash
./gradlew :system-tests:e2e-transfer-test:control-plane:run -Dedc.fs.config=provider.properties
```

**Terminal 2 -- Consumidor:**

```bash
./gradlew :system-tests:e2e-transfer-test:control-plane:run -Dedc.fs.config=consumer.properties
```

Espera a ver el mensaje `Runtime <id> ready` en cada terminal antes de continuar.

#### Paso 4: Verificar que estan corriendo

```bash
# Proveedor
curl http://localhost:18080/api/check/health

# Consumidor
curl http://localhost:28080/api/check/health
```

Ambos deben devolver `{"isSystemHealthy":true}`.

#### Puertos resultantes

| Servicio | Proveedor | Consumidor |
|----------|-----------|------------|
| Default (health) | `http://localhost:18080/api` | `http://localhost:28080/api` |
| Management API | `http://localhost:18181/api/management` | `http://localhost:28181/api/management` |
| Control API | `http://localhost:18183/api/control` | `http://localhost:28183/api/control` |
| DSP Protocol | `http://localhost:18282/api/dsp` | `http://localhost:28282/api/dsp` |

#### Parar los conectores

Pulsa `Ctrl+C` en cada terminal para detener el conector correspondiente.

### Coleccion Postman

El fichero [EDC_Policy_Evaluation_UseCases.postman_collection.json](docs/totbits/EDC_Policy_Evaluation_UseCases.postman_collection.json)
contiene **34 requests** organizados en 5 carpetas para ejecutar los 4 casos de uso de forma secuencial.

#### Importar en Postman

1. En Postman: **Import > File** > seleccionar `docs/totbits/EDC_Policy_Evaluation_UseCases.postman_collection.json`
2. Configurar las **variables de la coleccion** para que coincidan con los puertos de los conectores:

| Variable | Valor |
|----------|-------|
| `PROVIDER_MANAGEMENT_URL` | `http://localhost:18181/api/management` |
| `CONSUMER_MANAGEMENT_URL` | `http://localhost:28181/api/management` |
| `PROVIDER_DSP_URL` | `http://localhost:18282/api/dsp` |
| `PROVIDER_ID` | `provider` |
| `CONSUMER_CALLBACK_URL` | `http://localhost:28181/api/callbacks` |
| `CONSUMER_RECEIVE_URL` | `http://localhost:29999/api/receive-data` |
| `API_VERSION` | `v3` |
| `PROTOCOL` | `dataspace-protocol-http` |

#### Orden de ejecucion

| # | Carpeta | Requests | Que hace |
|---|---------|----------|----------|
| 0 | **Setup Proveedor** | 11 | Crea 3 assets, 4 policies (abierta, partners gold, denegada, temporal 7d) y 3 contract definitions |
| 1 | **catalog.request** | 3 | Solicita el catalogo completo, filtrado por asset, y un dataset especifico |
| 2 | **contract.negotiation** | 6 | Inicia negociacion, consulta estado, obtiene agreement, termina (opcional) |
| 3 | **transfer.process** | 8 | Inicia transferencia PUSH/PULL, consulta estado, suspende, reanuda, termina |
| 4 | **policy.monitor** | 8 | Flujo completo de streaming con policy temporal + policy de 60s para prueba rapida de expiracion |
| 5 | **Consultas Auxiliares** | 8 | Listar y eliminar recursos del proveedor |

Los scripts de test en Postman **propagan automaticamente** los IDs entre requests (`offer_id`, `negotiation_id`,
`agreement_id`, `transfer_id`), por lo que solo hay que ejecutarlos en orden.

---

## Contributing

See [how to contribute](https://github.com/eclipse-edc/eclipse-edc.github.io/blob/main/CONTRIBUTING.md).
