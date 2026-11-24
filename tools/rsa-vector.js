// 生成 CasCryptoTest 的期望值：用学校自己的 cas_security.js 算一遍
// 用法：node tools/rsa-vector.js <password>
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const code = fs.readFileSync(path.join(root, '..', '..', 'src-upstream', 'cas_security.js'), 'utf8');
const win = {};
new Function('window', code)(win);
win.RSAUtils.setMaxDigits(130);

const pk = {
  modulus:
    'de23ddc7387872e921bb242de5a92e9bcaf8bd0a1b4761505c32b91cbd6b1b30' +
    'e555446e0749016657b421f684715331cb7978f6db60abba35e7a661834f819d',
  exponent: '10001',
};

const pwd = process.argv[2] || 'Test1234';
console.log(
  win.RSAUtils.encryptedString(
    new win.RSAUtils.getKeyPair(pk.exponent, '', pk.modulus),
    pwd.split('').reverse().join(''),
  ),
);
