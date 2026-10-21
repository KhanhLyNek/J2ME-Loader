/**
 * MicroEmulator
 * Copyright (C) 2008 Bartek Teodorczyk <barteo@barteo.net>
 * Copyright (C) 2017-2018 Nikita Shakarun
 * Copyright (C) 2021-2022 Yury Kharchenko
 * <p>
 * It is licensed under the following two licenses as alternatives:
 * 1. GNU Lesser General Public License (the "LGPL") version 2.1 or any newer version
 * 2. Apache License (the "AL") Version 2.0
 * <p>
 * You may not use this file except in compliance with at least one of
 * the above two licenses.
 * <p>
 * You may obtain a copy of the LGPL at
 * http://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt
 * <p>
 * You may obtain a copy of the AL at
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the LGPL or the AL for the specific language governing permissions and
 * limitations.
 *
 * @version $Id$
 */

package org.microemu.android.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

public class AndroidProducer {

	public static byte[] instrument(final byte[] classData, String classFileName)
			throws IllegalArgumentException {
		ClassReader cr = new ClassReader(classData);
		if (!cr.getClassName().equals(classFileName.substring(0, classFileName.length() - 6))) {
			throw new IllegalArgumentException("Class name does not match path");
		}

		// Some older and obfuscated J2ME classes contain uncommon control-flow
		// patterns. ASM COMPUTE_MAXS may produce an operand-stack bound that
		// DX cannot simulate after MicroEmulator's instruction rewrites.
		// AndroidMethodVisitor reserves a small, bounded stack margin instead.
		// No new local variables are introduced by these rewrites.
		ClassWriter cw = new ClassWriter(0);
		ClassVisitor cv = new AndroidClassVisitor(cw);
		cr.accept(cv, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

		return cw.toByteArray();
	}
}
