import {
  MIR_ZERO,
  MIR_ONE,
  MIR_EIGHT,
  MIR_TEMP,
  MIR_IMMUTABLE_MEM,
  MIR_MOVE_TEMP,
  MIR_MOVE_IMMUTABLE_MEM,
  MIR_CALL_FUNCTION,
  MIR_CJUMP_FALLTHROUGH,
  MIR_JUMP,
  MIR_LABEL,
  MIR_RETURN,
} from '../../ast/mir';
import analyzeAvailableCopies from '../available-copy-analysis';

it('analyzeAvailableCopies test 1', () => {
  expect(
    analyzeAvailableCopies([
      /* 00 */ MIR_MOVE_TEMP(MIR_TEMP('a'), MIR_ONE),
      /* 01 */ MIR_MOVE_TEMP(MIR_TEMP('b'), MIR_ZERO),
      /* 02 */ MIR_MOVE_TEMP(MIR_TEMP('c'), MIR_EIGHT),
      /* 03 */ MIR_MOVE_TEMP(MIR_TEMP('x'), MIR_TEMP('a')),
      /* 04 */ MIR_MOVE_TEMP(MIR_TEMP('y'), MIR_TEMP('b')),
      /* 05 */ MIR_MOVE_TEMP(MIR_TEMP('z'), MIR_TEMP('c')),
      /* 06 */ MIR_MOVE_TEMP(MIR_TEMP('x'), MIR_TEMP('b')),
      /* 07 */ MIR_CALL_FUNCTION('fff', [], 'y'),
      /* 08 */ MIR_MOVE_TEMP(MIR_TEMP('z'), MIR_TEMP('x')),
      /* 09 */ MIR_CALL_FUNCTION('fff', []),
      /* 10 */ MIR_RETURN(),
    ])
  ).toEqual([
    /* 00 */ {},
    /* 01 */ {},
    /* 02 */ {},
    /* 03 */ {},
    /* 04 */ { x: 'a' },
    /* 05 */ { x: 'a', y: 'b' },
    /* 06 */ { x: 'a', y: 'b', z: 'c' },
    /* 07 */ { y: 'b', z: 'c', x: 'b' },
    /* 08 */ { z: 'c', x: 'b' },
    /* 09 */ { x: 'b', z: 'b' },
    /* 10 */ { x: 'b', z: 'b' },
  ]);
});

it('analyzeAvailableCopies test 2', () => {
  expect(
    analyzeAvailableCopies([
      /* 00 */ MIR_MOVE_TEMP(MIR_TEMP('a'), MIR_ONE),
      /* 01 */ MIR_MOVE_TEMP(MIR_TEMP('b'), MIR_ZERO),
      /* 02 */ MIR_CJUMP_FALLTHROUGH(MIR_TEMP('a'), 'true'),
      /* 03 */ MIR_MOVE_TEMP(MIR_TEMP('x'), MIR_TEMP('a')),
      /* 04 */ MIR_JUMP('end'),
      /* 05 */ MIR_LABEL('true'),
      /* 06 */ MIR_MOVE_TEMP(MIR_TEMP('x'), MIR_TEMP('b')),
      /* 07 */ MIR_LABEL('end'),
      /* 08 */ MIR_MOVE_TEMP(MIR_TEMP('y'), MIR_TEMP('x')),
      /* 09 */ MIR_MOVE_IMMUTABLE_MEM(MIR_IMMUTABLE_MEM(MIR_TEMP('y')), MIR_TEMP('x')),
    ])
  ).toEqual([
    /* 00 */ {},
    /* 01 */ {},
    /* 02 */ {},
    /* 03 */ {},
    /* 04 */ { x: 'a' },
    /* 05 */ {},
    /* 06 */ {},
    /* 07 */ {},
    /* 08 */ {},
    /* 09 */ { y: 'x' },
  ]);
});
