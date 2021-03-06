export type PrimitiveTypeName = 'unit' | 'bool' | 'int' | 'string';

export type PrimitiveType = { readonly type: 'PrimitiveType'; readonly name: PrimitiveTypeName };

export type IdentifierType = {
  readonly type: 'IdentifierType';
  readonly identifier: string;
  readonly typeArguments: readonly Type[];
};

export type TupleType = { readonly type: 'TupleType'; readonly mappings: readonly Type[] };

export type FunctionType = {
  readonly type: 'FunctionType';
  readonly argumentTypes: readonly Type[];
  readonly returnType: Type;
};

export type UndecidedType = { readonly type: 'UndecidedType'; readonly index: number };

export type Type = PrimitiveType | IdentifierType | TupleType | FunctionType | UndecidedType;

export const unitType: PrimitiveType = { type: 'PrimitiveType', name: 'unit' };
export const boolType: PrimitiveType = { type: 'PrimitiveType', name: 'bool' };
export const intType: PrimitiveType = { type: 'PrimitiveType', name: 'int' };
export const stringType: PrimitiveType = { type: 'PrimitiveType', name: 'string' };

export const identifierType = (
  identifier: string,
  typeArguments: readonly Type[] = []
): IdentifierType => ({
  type: 'IdentifierType',
  identifier,
  typeArguments,
});

export const tupleType = (mappings: readonly Type[]): TupleType => ({
  type: 'TupleType',
  mappings,
});

export const functionType = (argumentTypes: readonly Type[], returnType: Type): FunctionType => ({
  type: 'FunctionType',
  argumentTypes,
  returnType,
});

export class UndecidedTypes {
  private static nextUndecidedTypeIndex = 0;

  static next(): UndecidedType {
    const type = { type: 'UndecidedType', index: UndecidedTypes.nextUndecidedTypeIndex } as const;
    UndecidedTypes.nextUndecidedTypeIndex += 1;
    return type;
  }

  static nextN(n: number): readonly UndecidedType[] {
    const list: UndecidedType[] = [];
    for (let i = 0; i < n; i += 1) {
      list.push(UndecidedTypes.next());
    }
    return list;
  }

  // eslint-disable-next-line camelcase
  static resetUndecidedTypeIndex_ONLY_FOR_TEST(): void {
    UndecidedTypes.nextUndecidedTypeIndex = 0;
  }
}

export const prettyPrintType = (type: Type): string => {
  switch (type.type) {
    case 'PrimitiveType':
      return type.name;
    case 'IdentifierType':
      if (type.typeArguments.length === 0) {
        return type.identifier;
      }
      return `${type.identifier}<${type.typeArguments.map(prettyPrintType).join(', ')}>`;
    case 'TupleType':
      return `[${type.mappings.map(prettyPrintType).join(' * ')}]`;
    case 'FunctionType':
      return `(${type.argumentTypes.map(prettyPrintType).join(', ')}) -> ${prettyPrintType(
        type.returnType
      )}`;
    case 'UndecidedType':
      return '__UNDECIDED__';
  }
};

export const isTheSameType = (t1: Type, t2: Type): boolean => {
  switch (t1.type) {
    case 'PrimitiveType':
      return t2.type === 'PrimitiveType' && t1.name === t2.name;
    case 'IdentifierType':
      return (
        t2.type === 'IdentifierType' &&
        t1.identifier === t2.identifier &&
        t1.typeArguments.length === t2.typeArguments.length &&
        t1.typeArguments.every((t1Argument, index) =>
          isTheSameType(t1Argument, t2.typeArguments[index])
        )
      );
    case 'TupleType':
      return (
        t2.type === 'TupleType' &&
        t1.mappings.length === t2.mappings.length &&
        t1.mappings.every((t1Element, index) => isTheSameType(t1Element, t2.mappings[index]))
      );
    case 'FunctionType':
      return (
        t2.type === 'FunctionType' &&
        isTheSameType(t1.returnType, t2.returnType) &&
        t1.argumentTypes.length === t2.argumentTypes.length &&
        t1.argumentTypes.every((t1Argument, index) =>
          isTheSameType(t1Argument, t2.argumentTypes[index])
        )
      );
    case 'UndecidedType':
      return t2.type === 'UndecidedType' && t1.index === t2.index;
  }
};
