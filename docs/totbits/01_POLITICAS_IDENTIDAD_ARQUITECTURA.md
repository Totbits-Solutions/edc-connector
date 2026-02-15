# Arquitectura de Politicas e Identidad en EDC Connector

## Este repo ES el Control Plane (y mas)

No se necesita descargar nada aparte para el control plane. Vive en `core/control-plane/` y `extensions/control-plane/`. Lo que **si es un componente separado** es el **Identity Hub** — ese vive en otro repositorio (`eclipse-edc/IdentityHub`).

---

## Identity Hub — que hace y que NO esta aqui

El Identity Hub es un **componente externo** al Connector. Su responsabilidad es:

- **Secure Token Service (STS)** — crea y firma SI tokens (self-issued)
- **Gestion de claves** — almacena y rota key pairs
- **Presentation API** — endpoint que recibe peticiones de presentacion y devuelve Verifiable Presentations (VP)
- **Emision de credenciales** — emite VCs a participantes

### El Connector actua como cliente del Identity Hub

- Valida SI tokens recibidos
- Hace peticiones HTTP al CredentialService del otro participante
- Verifica criptograficamente las VCs/VPs (firma, revocacion, issuer)
- Resuelve DIDs (`did:web`) para encontrar endpoints

### Codigo relevante en este repo

| Ruta | Descripcion |
|------|-------------|
| `extensions/common/iam/decentralized-claims/` | Implementacion del protocolo DCP |
| `extensions/common/iam/verifiable-credentials/` | Validacion de credenciales |
| `extensions/common/iam/decentralized-identity/` | Resolucion de DIDs |
| `spi/common/decentralized-claims-spi/` | Interfaces (SecureTokenService, PresentationRequestService, etc.) |

---

## Sistema de Politicas — como funciona

### El Policy Engine (esta todo en este repo)

El motor de politicas vive en `core/common/lib/policy-engine-lib/` y **NO usa Open Policy Agent**. Es un **motor propio** del EDC basado en ODRL (Open Digital Rights Language).

Piezas clave:

| Clase/Interfaz | Ubicacion | Rol |
|----------------|-----------|-----|
| `PolicyEngine` | `spi/common/policy-engine-spi` | Interfaz principal. Evalua una `Policy` contra un contexto |
| `RuleFunction` | `spi/common/policy-engine-spi` | Funciones que evaluan reglas individuales (permissions, prohibitions, duties) |
| `PolicyValidatorFunction` | `spi/common/policy-engine-spi` | Validaciones pre/post sobre el contexto completo |
| `AtomicConstraintRuleFunction` | `spi/common/policy-engine-spi` | Evalua constraints atomicos (leftOperand, operator, rightOperand) |

---

## Cuando se evaluan las politicas (momentos clave)

Las politicas se evaluan en **4 momentos** durante el ciclo de vida, cada uno con su propio "scope":

### 1. `catalog.request`

Cuando un consumidor pide el catalogo. El proveedor filtra que assets/ofertas son visibles segun las **access policies** del `ContractDefinition`.

### 2. `contract.negotiation`

Durante la negociacion de contrato. El proveedor valida que la oferta del consumidor cumple las **contract policies**. Es aqui donde las credenciales verificables (extraidas del Identity Hub) se cruzan con las politicas.

### 3. `transfer.process`

Al iniciar una transferencia de datos. Se verifica que el acuerdo de contrato sigue siendo valido.

### 4. `policy.monitor`

**Post-transferencia continua.** El `PolicyMonitorManager` (`core/policy-monitor/`) re-evalua periodicamente las politicas de contratos activos (para transfers de larga duracion). Si una politica deja de cumplirse, puede revocar la transferencia.

---

## Flujo concreto en negociacion de contrato

En `core/control-plane/control-plane-contract-manager/`, el `ContractNegotiationManagerImpl` ejecuta:

```
1. Consumidor envia oferta → llega al proveedor
2. Proveedor busca ContractDefinition que contenga ese asset
3. Evalua ACCESS policy (este participante puede ver el asset?)
4. Evalua CONTRACT policy (cumple las condiciones del contrato?)
   → Aqui el PolicyEngine recibe un PolicyContext con:
     - ClaimToken (credenciales verificables del participante)
     - ContractOffer
     - Asset metadata
5. Si pasa → genera ContractAgreement
6. Si falla → rechaza la negociacion
```

---

## Como se conectan las credenciales con las politicas

El puente es el **`ClaimToken`**. Cuando el `IdentityService` (implementado por `DcpIdentityService`) verifica el token JWT del otro participante:

```
1. Pide presentacion de credenciales al Identity Hub del otro participante
2. Valida las VCs/VPs (firma, revocacion, issuer, formato)
3. Empaqueta todo en un ClaimToken (claims extraidos de las credenciales)
4. El PolicyEngine recibe ese ClaimToken y lo evalua contra las reglas de la politica
```

---

## Sobre Open Policy Agent (OPA)

**No hay integracion con OPA en este repositorio.** El EDC usa su propio motor de politicas basado en ODRL.

Sin embargo, el `PolicyEngine` es extensible: se podria escribir una extension que delegue evaluacion a OPA registrando `RuleFunction`s personalizadas que hagan llamadas HTTP a un servidor OPA.

---

## Resumen de componentes

| Componente | Donde vive | Que hace |
|---|---|---|
| **Control Plane** | Este repo: `core/control-plane/` | Negociacion de contratos, catalogo, transferencias |
| **Policy Engine** | Este repo: `core/common/lib/policy-engine-lib/` | Evalua politicas ODRL (propio, no OPA) |
| **Policy Monitor** | Este repo: `core/policy-monitor/` | Re-evalua politicas en transfers activos |
| **IAM/DCP client** | Este repo: `extensions/common/iam/` | Valida credenciales, resuelve DIDs |
| **Identity Hub** | **Otro repo** (`eclipse-edc/IdentityHub`) | STS, emision de VCs, Presentation API |
