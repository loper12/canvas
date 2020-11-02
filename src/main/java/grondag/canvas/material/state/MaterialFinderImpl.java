package grondag.canvas.material.state;

import grondag.frex.api.material.MaterialFinder;

// WIP: expose attributes in FREX
// WIP: use decal layer in JMX, RenderBender and XB/XM to improve performance for mult-layer blocks
public class MaterialFinderImpl extends AbstractStateFinder<MaterialFinderImpl, RenderMaterialImpl> implements MaterialFinder {
	@Override
	protected synchronized RenderMaterialImpl findInner() {
		RenderMaterialImpl result = RenderMaterialImpl.MAP.get(bits);

		if (result == null) {
			result = new RenderMaterialImpl(bits);
			RenderMaterialImpl.MAP.put(bits, result);
			RenderMaterialImpl.LIST.add(result);
		}

		return result;
	}

	@Override
	protected RenderMaterialImpl missing() {
		return RenderMaterialImpl.MISSING;
	}

	private static ThreadLocal<MaterialFinderImpl> FINDER = ThreadLocal.withInitial(MaterialFinderImpl::new);

	public static MaterialFinderImpl threadLocal() {
		final MaterialFinderImpl result = FINDER.get();
		result.clear();
		return result;
	}
}