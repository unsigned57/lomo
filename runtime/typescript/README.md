# @boltffi/runtime

WASM runtime for [BoltFFI](https://boltffi.dev) generated TypeScript bindings.

## Installation

```bash
npm install @boltffi/runtime
```

## Usage

This package is used internally by BoltFFI-generated WASM bindings. You don't need to install it directly - it's bundled with your generated package when you run `boltffi pack wasm`.

### Example

When you run `boltffi pack wasm`, the generated TypeScript uses this runtime:

```typescript
import { distance } from 'your-package';

const d = distance({ x: 0, y: 0 }, { x: 3, y: 4 });
console.log(d); // 5.0
```

## Documentation

- [BoltFFI Docs](https://boltffi.dev/docs)
- [WASM Guide](https://boltffi.dev/docs/getting-started#use-in-typescript)

## License

MIT
